package models.actors

import scala.util.Try
import scala.util.control.NonFatal

import akka.actor.{ Actor, ActorLogging, ActorRef, Props }
import models.{ HandlerResult, Origin, RequestEnvelope, ResponseEnvelope }
import models.rpc._
import play.api.cache.CacheApi

/**
 * Aggregates the requests and fans out the responses. Serves as the gateway for a particular Responder
 * to cache requests and avoid lower the load on the Responder.
 *
 * - for requests: caches them and returns only those that need to be forwarded to the client; returns
 *                 the responses that need to be delivered to the originator, if immediately available
 * - for responses: returns the responses grouped by originator, so that they could be delivered to
 *                  the requesters in batches.
 */
class RRProcessorActor(linkPath: String, cache: CacheApi) extends Actor with ActorLogging {
  import RRProcessorActor._

  type RequestHandler = PartialFunction[(String, DSARequest), HandlerResult]

  // lookup registries for SID (Subscribe/Unsubscribe) and RID (all other) requests
  private val ridRegistry = new CallRegistry(1)
  private val sidRegistry = new CallRegistry(1)

  private val ownPath = linkPath + Suffix
  private val ownId = s"Link[$ownPath]"

  /**
   * Register the processor in the cache.
   */
  override def preStart() = {
    cache.set(ownPath, self)
    log.info(s"$ownId: responder processor initialized for $linkPath")
  }

  /**
   * Receives requests from Requesters and responses from the associated Responder.
   * Upon processing, it delivers the messages to either the original Requester or the Responder.
   */
  def receive = {
    case re @ RequestEnvelope(from, to, requests) =>
      log.debug(s"$ownId: received $re")
      val result = processRequests(from, requests)
      route(RequestEnvelope(from, to, result.requests), ownPath, to)
      route(ResponseEnvelope(to, from, result.responses), ownPath, from)

    case re @ ResponseEnvelope(from, to, responses) =>
      log.debug(s"$ownId: received $re")
      processResponses(responses) foreach {
        case (to, rsps) => route(ResponseEnvelope(linkPath, to, rsps), ownPath, to)
      }
  }

  /**
   * Processes the requests and returns requests that need to be forwaded to their destinations
   * as well as the responses that need to be delivered to the originators.
   */
  def processRequests(from: String, requests: Seq[DSARequest]): HandlerResult = {
    val handler = handleListRequest orElse handlePassthroughRequest orElse
      handleSubscribeRequest orElse handleUnsubscribeRequest orElse handleCloseRequest

    val results = requests map (request => try {
      handler(from -> request)
    } catch {
      case NonFatal(e) => log.error(s"$ownId: error handling request $request - {}", e); HandlerResult.Empty
    })

    log.debug("RID lookups: " + ridRegistry.info)
    log.debug("SID lookups: " + sidRegistry.info)

    HandlerResult.flatten(results)
  }

  /**
   * Processes the responses and returns the translated ones groupped by their destinations.
   */
  def processResponses(responses: Seq[DSAResponse]): Map[String, Seq[DSAResponse]] = {
    val handler = handleSubscribeResponse orElse handleNonSubscribeResponse

    val results = responses flatMap handler

    log.debug("RID lookups: " + ridRegistry.info)
    log.debug("SID lookups: " + sidRegistry.info)

    results groupBy (_._1) mapValues (_.map(_._2))
  }

  /**
   * Handles List request.
   */
  private val handleListRequest: RequestHandler = {
    case (from, ListRequest(rid, path)) =>
      val origin = Origin(from, rid)
      ridRegistry.lookupByPath(path) match {
        case None =>
          val targetRid = ridRegistry.saveLookup(origin, Some(path))
          HandlerResult(ListRequest(targetRid, translatePath(path)))
        case Some(rec) =>
          ridRegistry.addOrigin(origin, rec)
          rec.lastResponse map { rsp =>
            HandlerResult(rsp.copy(rid = origin.sourceId))
          } getOrElse HandlerResult.Empty
      }
  }

  /**
   * Handles Set, Remove and Invoke requests.
   */
  private val handlePassthroughRequest: RequestHandler = {

    def tgtId(from: String, srcId: Int) = ridRegistry.saveLookup(Origin(from, srcId))

    val pass: PartialFunction[(String, DSARequest), DSARequest] = {
      case (from, SetRequest(rid, path, value, permit)) =>
        SetRequest(tgtId(from, rid), translatePath(path), value, permit)
      case (from, RemoveRequest(rid, path)) =>
        RemoveRequest(tgtId(from, rid), translatePath(path))
      case (from, InvokeRequest(rid, path, params, permit)) if path.endsWith("/" + AddAttributeAction) =>
        val attrName = params("name").value.toString
        val attrPath = path.dropRight(AddAttributeAction.size) + attrName
        val attrValue = params("value")
        SetRequest(tgtId(from, rid), translatePath(attrPath), attrValue, permit)
      case (from, InvokeRequest(rid, path, params, permit)) if path.endsWith("/" + SetValueAction) =>
        val attrPath = path.dropRight(SetValueAction.size + 1)
        val attrValue = params("value")
        SetRequest(tgtId(from, rid), translatePath(attrPath), attrValue, permit)
      case (from, InvokeRequest(rid, path, params, permit)) =>
        InvokeRequest(tgtId(from, rid), translatePath(path), params, permit)
    }

    pass andThen HandlerResult.apply
  }

  /**
   * Handles Subscribe request.
   */
  private val handleSubscribeRequest: RequestHandler = {
    case (from, req @ SubscribeRequest(rid, _)) =>
      val srcPath = req.path // to ensure there's only one path (see requester actor)
      val sidOrigin = Origin(from, srcPath.sid)
      val result = sidRegistry.lookupByPath(srcPath.path) match {
        case None =>
          val targetSid = sidRegistry.saveLookup(sidOrigin, Some(srcPath.path), None)
          val targetRid = ridRegistry.saveEmpty
          val tgtPath = srcPath.copy(path = translatePath(srcPath.path), sid = targetSid)
          HandlerResult(SubscribeRequest(targetRid, tgtPath))
        case Some(rec) =>
          sidRegistry.addOrigin(sidOrigin, rec)
          rec.lastResponse map { rsp =>
            val sourceRow = replaceSid(rsp.updates.get.head, sidOrigin.sourceId)
            val update = DSAResponse(0, rsp.stream, Some(List(sourceRow)), rsp.columns, rsp.error)
            HandlerResult(update)
          } getOrElse HandlerResult.Empty
      }
      result.copy(responses = DSAResponse(rid, Some(StreamState.Closed)) +: result.responses)
  }

  /**
   * Handles Unsubscribe request.
   */
  private val handleUnsubscribeRequest: RequestHandler = {
    case (from, req @ UnsubscribeRequest(rid, _)) =>
      val origin = Origin(from, req.sid) // to ensure there's only one sid (see requester actor)
      sidRegistry.removeOrigin(origin) map { rec =>
        val wsReqs = if (rec.origins.isEmpty) {
          sidRegistry.removeLookup(rec)
          List(UnsubscribeRequest(ridRegistry.saveEmpty, rec.targetId))
        } else Nil
        HandlerResult(wsReqs, List(DSAResponse(rid, Some(StreamState.Closed))))
      } getOrElse {
        log.warning(s"$ownId: did not find the original Subscribe for SID=${req.sid}")
        HandlerResult.Empty
      }
  }

  /**
   * Handles Close request.
   */
  private val handleCloseRequest: RequestHandler = {
    case (from, CloseRequest(rid)) =>
      val origin = Origin(from, rid)
      val record = ridRegistry.removeOrigin(origin)
      record match {
        case None =>
          log.warning(s"$ownId: did not find the original request for Close($rid)")
          HandlerResult.Empty
        case Some(rec) =>
          if (rec.origins.isEmpty) ridRegistry.removeLookup(rec)
          val reqs = if (rec.origins.isEmpty) List(CloseRequest(rec.targetId)) else Nil
          val rsps = if (rec.path.isDefined) List(DSAResponse(rid, Some(StreamState.Closed))) else Nil
          HandlerResult(reqs, rsps)
      }
  }

  /**
   * Splits the response updates in individual row, translates each update's SID into
   * (potentially) multiple source SIDs and creates one response per source SID.
   */
  private def handleSubscribeResponse: PartialFunction[DSAResponse, Seq[(String, DSAResponse)]] = {
    case rsp @ DSAResponse(0, stream, updates, columns, error) =>
      val list = updates.getOrElse(Nil)
      if (list.isEmpty) {
        log.warning(s"Cannot find updates in Subscribe response $rsp")
        Nil
      } else list flatMap { row =>
        val targetSid = extractSid(row)
        val rec = sidRegistry.lookupByTargetId(targetSid).get
        rec.lastResponse = Some(DSAResponse(0, stream, Some(List(row)), columns, error))
        if (stream == Some(StreamState.Closed))
          sidRegistry.removeLookup(rec)
        rec.origins map { origin =>
          val sourceRow = replaceSid(row, origin.sourceId)
          val response = DSAResponse(0, stream, Some(List(sourceRow)), columns, error)
          (origin.source, response)
        }
      }
  }

  /**
   * Handles a non-Subscribe response.
   */
  private val handleNonSubscribeResponse: PartialFunction[DSAResponse, Seq[(String, DSAResponse)]] = {
    case response if response.rid != 0 =>
      val record = ridRegistry.lookupByTargetId(response.rid)
      record match {
        case None =>
          log.warning(s"$ownId: did not find the route for $response")
          Nil
        case Some(rec) =>
          rec.lastResponse = Some(response)
          if (response.stream == Some(StreamState.Closed))
            ridRegistry.removeLookup(rec)
          rec.origins map { origin =>
            (origin.source, response.copy(rid = origin.sourceId))
          } toSeq
      }
  }

  /**
   * Removes the linkPath prefix from the path.
   */
  private def translatePath(path: String) = {
    val chopped = path.drop(linkPath.size)
    if (!chopped.startsWith("/")) "/" + chopped else chopped
  }

  /**
   * Routes a message resolving the target actor by the cache lookup.
   */
  private def route(msg: Any, from: String, to: String) = Try {
    val ref = cache.get[ActorRef](to).get
    log.debug(s"Sending $msg from [$from] to [$to]")
    ref ! msg
  } recover {
    case e: NoSuchElementException => log.error(s"Actor not found for path [$to]")
    case NonFatal(e)               => log.error(s"Error sending message to [$to]: {}", e.getMessage)
  }
}

/**
 * Factory for [[RRProcessorActor]] instances.
 */
object RRProcessorActor {
  val AddAttributeAction = "addAttribute"
  val SetValueAction = "setValue"

  val Suffix = "#processor"

  def props(linkPath: String, cache: CacheApi) = Props(new RRProcessorActor(linkPath, cache))
}
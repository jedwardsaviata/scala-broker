package models.actors

import org.scalatest.{ MustMatchers, WordSpecLike }

import com.typesafe.config.ConfigFactory

import akka.actor.{ ActorSystem, Props, actorRef2Scala }
import akka.testkit.{ TestKit, TestProbe }
import models.Settings
import models.rpc._
import net.sf.ehcache.CacheManager
import play.api.Configuration
import play.api.cache.EhCacheApi

/**
 * RequesterActor test suite.
 */
class RequesterActorSpec extends TestKit(ActorSystem()) with WordSpecLike with MustMatchers {

  val settings = new Settings(new Configuration(ConfigFactory.load))
  val connInfo = ConnectionInfo("testDsId", true, false, "/reqPath")
  val cache = new EhCacheApi(CacheManager.getInstance.addCacheIfAbsent("test"))

  val sysProbe = TestProbe()
  cache.set("/sys", sysProbe.ref)

  val reqActor = system.actorOf(Props(new RequesterActor(testActor, settings, connInfo, cache)))

  "RequesterActor" should {
    "send 'allowed' message on startup" in {
      expectMsg(AllowedMessage(true, settings.Salt))
    }
    "return ack for a request message" in {
      reqActor ! RequestMessage(101, None, Nil)
      expectMsg(PingMessage(1, Some(101)))
    }
    "route requests to appropriate targets" in {
      reqActor ! RequestMessage(104, None, List(ListRequest(111, "/sys")))
      expectMsg(PingMessage(2, Some(104)))
      sysProbe.expectMsg(RequestEnvelope(ListRequest(111, "/sys")))
    }
    "route responses to socket" in {
      reqActor ! ResponseEnvelope(DSAResponse(101, Some(StreamState.Open)))
      expectMsg(ResponseMessage(3, None, List(DSAResponse(101, Some(StreamState.Open)))))
    }
  }
}
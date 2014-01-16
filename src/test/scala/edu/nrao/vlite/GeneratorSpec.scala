package edu.nrao.vlite

import akka.actor.{ ActorRef, Actor, Props, ActorSystem }
import akka.testkit.{ ImplicitSender, TestKit, TestActorRef }
import scala.concurrent.duration._
import org.scalatest._

class GeneratorSpec(_system: ActorSystem)
    extends TestKit(_system)
    with ImplicitSender
    with FlatSpecLike
    with BeforeAndAfterAll
    with Matchers {
  
  def this() = this(ActorSystem("GeneratorSpec"))

  var transporter: Option[ActorRef] = None

  override def beforeAll() {
    transporter = Some(system.actorOf(
      Props(classOf[GeneratorSpec.TestTransporter], testActor)))
    transporter map (_ ! Transporter.Start)
  }

  override def afterAll() {
    transporter map (_ ! Transporter.Stop)
    system.shutdown()
    system.awaitTermination(10.seconds)
  }

  def testGenerator(threadID: Int, stationID: Int, rate: Int) = {
    system.actorOf(Generator.props(
      threadID,
      stationID,
      transporter.get,
      Generator.framesPerSec / rate))
  }

  "A Generator" should "start in the idle state" in {
    val generator = testGenerator(0, 0, 100)
    expectNoMsg
  }

  it should "generate frames after start" in {
    val generator = testGenerator(0, 0, 100)
    generator ! Generator.Start
    expectMsgClass(classOf[GeneratorSpec.Packet])
    generator ! Generator.Stop
  }

  it should "not generate frames after stop" in {
    import system._
    val generator = testGenerator(0, 0, 100)
    generator ! Generator.Start
    scheduler.scheduleOnce(1.seconds, generator, Generator.Stop)
    receiveWhile(1200.millis) {
      case _: GeneratorSpec.Packet => true
    }
    expectNoMsg
  }

  it should "allow restarts" in {
    import system._
    val generator = testGenerator(0, 0, 100)
    generator ! Generator.Start
    scheduler.scheduleOnce(500.millis, generator, Generator.Stop)
    receiveWhile(700.millis) {
      case _: GeneratorSpec.Packet => true
    }
    expectNoMsg
    generator ! Generator.Start
    expectMsgClass(classOf[GeneratorSpec.Packet])
    generator ! Generator.Stop
  }

  it should "generate frames at the desired rate" in {
    import system._
    val generator = testGenerator(0, 0, 100)
    generator ! Generator.Start
    scheduler.scheduleOnce(1.seconds, generator, Generator.Stop)
    val frames = receiveWhile(1200.millis) {
      case _: GeneratorSpec.Packet => true
    }
    frames.length should === (100 +- 1)
  }

  it should "generate frames with the provided threadID and stationID" in {
    import system._
    val threadID = 111
    val stationID = 890
    val generator = testGenerator(threadID, stationID, 100)
    generator ! Generator.Start
    val packet = expectMsgClass(classOf[GeneratorSpec.Packet])
    generator ! Generator.Stop
    val frame = packet.buffer.asInstanceOf[TypedBuffer[Ethernet[VLITEFrame]]].
      read.payload
    frame.header.threadID should === (threadID)
    frame.header.stationID should === (stationID)
  }

  it should "maintain a maximum latency of one second" in {
    import system._
    val generator = testGenerator(0, 0, 100)
    ignoreMsg {
      case _: GeneratorSpec.Packet => true
    }
    generator ! Generator.Start
    receiveWhile(2.seconds) {
      case _ => true
    }
    val beZeroOrOne = be === 0 or be === 1
    def latency = {
      generator ! Generator.GetLatency
      expectMsgClass(classOf[Generator.Latency]) match {
        case Generator.Latency(latency) =>
          latency
      }
    }
    latency should beZeroOrOne
    receiveWhile(2.seconds) {
      case _ => true
    }
    latency should beZeroOrOne
    generator ! Generator.Stop
  }
}

object GeneratorSpec {
  case class Packet(buffer: TypedBuffer[_])

  class TestTransporter(destination: ActorRef) extends Transporter {
    protected def sendBuffer(buffer: TypedBuffer[_]) {
      destination ! Packet(buffer)
    }
  }
}

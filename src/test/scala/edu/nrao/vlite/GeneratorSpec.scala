package edu.nrao.vlite

import akka.actor.{ ActorRef, Actor, Props, ActorSystem, PoisonPill }
import akka.io.{ PipelineFactory, PipelinePorts }
import akka.testkit.{ ImplicitSender, TestKit, TestActorRef }
import akka.util.ByteString
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
  }

  override def afterAll() {
    transporter map (_ ! PoisonPill)
    system.shutdown()
    system.awaitTermination(10.seconds)
  }

  val arraySize = 5000

  val decimation = 100

  object VLITEConfig extends VLITEConfig {
    val dataArraySize = arraySize
    lazy val initDataArray = Seq.fill[Byte](dataArraySize)(0)
  }

  val PipelinePorts(_, vliteEventPipeline, _) =
    PipelineFactory.buildFunctionTriple(VLITEConfig, VLITEStage)

  def testGenerator(threadID: Int, stationID: Int) = {
    system.actorOf(Generator.props(
      threadID,
      stationID,
      transporter.get,
      decimation = decimation,
      arraySize = arraySize))
  }

  "A Generator" should "generate frames after start" in {
    val generator = testGenerator(0, 0)
    expectMsgClass(classOf[GeneratorSpec.Packet])
    generator ! PoisonPill
  }

  it should "not generate frames after stop" in {
    import system._
    val generator = testGenerator(0, 0)
    scheduler.scheduleOnce(1.seconds, generator, PoisonPill)
    receiveWhile(1500.millis) {
      case _: GeneratorSpec.Packet => true
    }
    expectNoMsg
  }

  it should "generate frames at the desired rate" in {
    import system._
    val generator = testGenerator(0, 0)
    scheduler.scheduleOnce(5.seconds, generator, PoisonPill)
    val frames = receiveWhile(6.seconds) {
      case _: GeneratorSpec.Packet => true
    }
    frames.length should === (5 * VLITEConfig.framesPerSec / decimation +- 1)
  }

  it should "generate frames with the provided threadID and stationID" in {
    import system._
    val threadID = 111
    val stationID = 890
    val generator = testGenerator(threadID, stationID)
    val packet = expectMsgClass(classOf[GeneratorSpec.Packet])
    generator ! PoisonPill
    val header = vliteEventPipeline(packet.byteString)._1.head
    header.threadID should === (threadID)
    header.stationID should === (stationID)
  }

  it should "maintain a maximum latency of one second" in {
    import system._
    val generator = testGenerator(0, 0)
    ignoreMsg {
      case _: GeneratorSpec.Packet => true
    }
    receiveWhile(2.seconds) {
      case _ => true
    }
    val beZeroOrOne = be(0) or be(1)
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
    generator ! PoisonPill
  }
}

object GeneratorSpec {
  case class Packet(byteString: ByteString)

  class TestTransporter(destination: ActorRef) extends Transporter {
    protected def send(byteString: ByteString) = {
      destination ! Packet(byteString)
      true
    }
  }
}

package edu.nrao.vlite

import akka.actor.{ ActorRef, Actor, Props, ActorSystem }
import akka.testkit.{ ImplicitSender, TestKit, TestActorRef }
import scala.concurrent.duration._
import org.scalatest._

class TransporterSpec(_system: ActorSystem)
    extends TestKit(_system)
    with ImplicitSender
    with FlatSpecLike
    with BeforeAndAfterAll
    with Matchers {
  import Transporter._
  
  def this() = this(ActorSystem("TransporterSpec"))

  override def afterAll() {
    system.shutdown()
    system.awaitTermination(10.seconds)
  }

  val testEthernet0 = Ethernet(
    source = MAC(0,1,2,3,4,5),
    destination = MAC(10,11,12,13,14,15),
    payload = VLITEFrame(VLITEHeader(
      isInvalidData = false,
      secFromRefEpoch = 1000,
      refEpoch = 1,
      numberWithinSec = 50,
      threadID = 1,
      stationID = 6)))

  val testEthernet1 = Ethernet(
    source = MAC(0,2,1,3,5,4),
    destination = MAC(10,12,13,11,15,14),
    payload = VLITEFrame(VLITEHeader(
      isInvalidData = false,
      secFromRefEpoch = 1002,
      refEpoch = 1,
      numberWithinSec = 51,
      threadID = 0,
      stationID = 4)))

  "A Transporter" should "start with a buffer count of 0" in {
    val transporter = system.actorOf(
      Props(classOf[TransporterSpec.TestTransporter], testActor))
    transporter ! GetBufferCount
    expectMsg(BufferCount(0))
  }

  it should "drop frames when idle" in {
    val transporter = system.actorOf(
      Props(classOf[TransporterSpec.TestTransporter], testActor))
    transporter ! Transport(testEthernet0.frame)
    expectNoMsg
  }

  it should "transport frame to destination after start" in {
    val transporter = system.actorOf(
      Props(classOf[TransporterSpec.TestTransporter], testActor))
    transporter ! StartTransport
    val frame = testEthernet0.frame
    transporter ! Transport(frame)
    expectMsg(TransporterSpec.Packet(frame))
  }

  it should "start and stop transporting as commanded" in {
    val transporter = system.actorOf(
      Props(classOf[TransporterSpec.TestTransporter], testActor))
    val frame0 = testEthernet0.frame
    transporter ! StartTransport
    transporter ! Transport(frame0)
    expectMsg(TransporterSpec.Packet(frame0))
    transporter ! StopTransport
    transporter ! Transport(frame0)
    expectNoMsg
    transporter ! StartTransport
    transporter ! Transport(frame0)
    expectMsg(TransporterSpec.Packet(frame0))
  }

  it should "only count transported buffers" in {
    val transporter = system.actorOf(
      Props(classOf[TransporterSpec.TestTransporter], testActor))
    val numF = 4
    val frame0 = testEthernet0.frame
    transporter ! StartTransport
    for (i <- 0 until numF) { transporter ! Transport(frame0) }
    receiveN(numF)
    transporter ! StopTransport
    for (i <- 0 until numF) { transporter ! Transport(frame0) }
    transporter ! StartTransport
    for (i <- 0 until numF) { transporter ! Transport(frame0) }
    receiveN(numF)
    transporter ! GetBufferCount
    expectMsg(BufferCount(2 * numF))
  }

  it should "not buffer packets received while idle" in {
    val transporter = system.actorOf(
      Props(classOf[TransporterSpec.TestTransporter], testActor))
    val frame0 = testEthernet0.frame
    transporter ! StartTransport
    transporter ! Transport(frame0)
    expectMsg(TransporterSpec.Packet(frame0))
    transporter ! StopTransport
    transporter ! Transport(frame0)
    val frame1 = testEthernet1.frame
    transporter ! StartTransport
    transporter ! Transport(frame1)
    expectMsg(TransporterSpec.Packet(frame1))
  }
}

object TransporterSpec {
  case class Packet(buffer: TypedBuffer[_])

  class TestTransporter(destination: ActorRef) extends Transporter {
    protected def sendBuffer(buffer: TypedBuffer[_]) {
      destination ! Packet(buffer)
    }
  }
}

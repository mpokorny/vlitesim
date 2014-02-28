package edu.nrao.vlite

import akka.actor.{ ActorRef, Actor, Props, ActorSystem, PoisonPill }
import akka.testkit.{ ImplicitSender, TestKit, TestActorRef }
import akka.util.ByteString
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

  "A Transporter" should "start with a buffer count of 0" in {
    val transporter = system.actorOf(
      Props(classOf[TransporterSpec.TestTransporter], testActor))
    transporter ! GetBufferCount
    expectMsg(BufferCount(0))
    transporter ! PoisonPill
  }

  it should "transport frame to destination after start" in {
    val transporter = system.actorOf(
      Props(classOf[TransporterSpec.TestTransporter], testActor))
    val frame = ByteString("hello")
    transporter ! Transport(frame)
    expectMsg(TransporterSpec.Packet(frame))
    transporter ! PoisonPill
  }

  it should "count transported buffers" in {
    val transporter = system.actorOf(
      Props(classOf[TransporterSpec.TestTransporter], testActor))
    val numF = 4
    val frame0 = ByteString("hello")
    for (i <- 0 until numF) { transporter ! Transport(frame0) }
    receiveN(numF)
    transporter ! GetBufferCount
    expectMsg(BufferCount(numF))
    transporter ! PoisonPill
  }
}

object TransporterSpec {
  case class Packet(byteString: ByteString)

  class TestTransporter(destination: ActorRef) extends Transporter {
    protected def send(byteString: ByteString) = {
      destination ! Packet(byteString)
      true
    }
  }
}

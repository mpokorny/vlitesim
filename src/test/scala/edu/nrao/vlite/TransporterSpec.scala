//
// Copyright © 2014 Associated Universities, Inc. Washington DC, USA.
//
// This file is part of vlitesim.
//
// vlitesim is free software: you can redistribute it and/or modify it under the
// terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version.
//
// vlitesim is distributed in the hope that it will be useful, but WITHOUT ANY
// WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
// A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along with
// vlitesim.  If not, see <http://www.gnu.org/licenses/>.
//
package edu.nrao.vlite

import akka.actor.{ ActorRef, Actor, Props, ActorSystem, PoisonPill }
import akka.testkit.{ ImplicitSender, TestKit, TestActorRef }
import akka.util.ByteString
import scala.concurrent.Future
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
      Props(
        classOf[Transporter],
        Props(classOf[TransporterSpec.TestSender], testActor)))
    transporter ! GetBufferCount
    expectMsg(BufferCount(0))
    transporter ! PoisonPill
  }

  it should "transport frame to destination after start" in {
    import system._
    val transporter = system.actorOf(
      Props(
        classOf[Transporter],
        Props(classOf[TransporterSpec.TestSender], testActor)))
    val frame = ByteString("hello")
    transporter ! Transport(Vector(Future(frame)))
    expectMsg(TransporterSpec.Packet(frame))
    transporter ! PoisonPill
  }

  it should "count transported buffers" in {
    import system._
    val transporter = system.actorOf(
      Props(
        classOf[Transporter],
        Props(classOf[TransporterSpec.TestSender], testActor)))
    val numF = 4
    val frame0 = Future(ByteString("hello"))
    transporter ! Transport(Vector.fill(numF)(frame0))
    receiveN(numF)
    transporter ! GetBufferCount
    expectMsg(BufferCount(numF))
    transporter ! PoisonPill
  }
}

object TransporterSpec {
  case class Packet(byteString: ByteString)

  class TestSender(destination: ActorRef) extends ByteStringsSender {
    protected def send(byteString: ByteString) = {
      destination ! Packet(byteString)
      true
    }
  }
}

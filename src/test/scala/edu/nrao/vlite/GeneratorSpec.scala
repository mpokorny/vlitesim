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
import akka.io.{ PipelineFactory, PipelinePorts }
import akka.testkit.{ ImplicitSender, TestKit, TestActorRef }
import akka.util.{ ByteString, Timeout }
import scala.concurrent.duration._
import org.scalatest._

class GeneratorSpec(_system: ActorSystem)
    extends TestKit(_system)
    with ImplicitSender
    with FlatSpecLike
    with BeforeAndAfterAll
    with Matchers {
  spec =>

  def this() = this(ActorSystem("GeneratorSpec"))

  var transporter: Option[ActorRef] = None

  override def beforeAll() {
    transporter = Some(system.actorOf(
      Props(
        classOf[Transporter],
        Props(classOf[GeneratorSpec.TestSender], testActor))))
  }

  override def afterAll() {
    transporter map (_ ! PoisonPill)
    system.shutdown()
    system.awaitTermination(10.seconds)
  }

  val arraySize = 1000

  val decimation = 100

  object VLITEConfigZeroData extends VLITEConfigZeroData {
    implicit val executionContext = system.dispatcher
    val dataArraySize = arraySize
  }

  val PipelinePorts(_, evtPipeZero, _) =
    PipelineFactory.buildFunctionTriple(VLITEConfigZeroData, VLITEStage)

  val simParams = SimParams(888L, Vector(0.1, -0.2, 1.0, -0.2, 0.1), 6.2, 128)

  object VLITEConfigSimData extends VLITEConfigSimData {
    val SimParams(seed, filter, scale, offset) = simParams
    val dataArraySize = arraySize
    implicit val timeout = Timeout(1, SECONDS)
    val bufferSize = 2
    lazy val actorRefFactory = system
    implicit lazy val executionContext = system.dispatcher
  }

  val PipelinePorts(_, evtPipeSim, _) =
    PipelineFactory.buildFunctionTriple(VLITEConfigSimData, VLITEStage)

  def testGenerator(
    threadID: Int,
    stationID: Int,
    decimation: Int = decimation,
    simData: Boolean = false) = {
    system.actorOf(Generator.props(
      threadID,
      stationID,
      transporter.get,
      decimation = decimation,
      arraySize = arraySize,
      simParams = if (simData) Some(simParams) else None))
  }

  def tossFrames(duration: Duration) {
    receiveWhile(duration) {
      case _ => true
    }
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
    tossFrames(2.seconds)
    scheduler.scheduleOnce(5.seconds, generator, PoisonPill)
    val frames = receiveWhile(6.seconds) {
      case _: GeneratorSpec.Packet => true
    }
    frames.length should === (
      5 * VLITEConfigZeroData.framesPerSec / decimation +- 50)
  }

  it should "generate frames with the provided threadID and stationID" in {
    import system._
    val threadID = 111
    val stationID = 890
    val generator = testGenerator(threadID, stationID)
    val packet = expectMsgClass(classOf[GeneratorSpec.Packet])
    generator ! PoisonPill
    val frame = evtPipeZero(packet.byteString)._1.head
    frame.header.threadID should === (threadID)
    frame.header.stationID should === (stationID)
  }

  it should "generate simulated frames upon request" in {
    import system._
    val decimation = 512
    val generator = testGenerator(0, 0, decimation = decimation, simData = true)
    tossFrames(2.seconds)
    scheduler.scheduleOnce(5.seconds, generator, PoisonPill)
    val frames = receiveWhile(6.seconds) {
      case GeneratorSpec.Packet(bs) => bs
    }
    val dataFrames = frames map { f =>
      evtPipeSim(f)._1.head
    }
    dataFrames.length should === (
      5 * VLITEConfigSimData.framesPerSec / decimation +- 5)
  }
}

object GeneratorSpec {
  case class Packet(byteString: ByteString)

  class TestSender(destination: ActorRef) extends ByteStringsSender {
    protected def send(byteString: ByteString) = {
      destination ! Packet(byteString)
      true
    }
  }
}

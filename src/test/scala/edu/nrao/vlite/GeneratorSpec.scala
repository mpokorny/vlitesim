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
import java.io.FileOutputStream
import org.joda.time.{ DateTime, DateTimeZone }

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

  val pace = 10.millis

  object VLITEConfig extends VLITEConfigZeroData {
    val dataArraySize = arraySize
  }

  val PipelinePorts(_, evtPipe, _) =
    PipelineFactory.buildFunctionTriple(VLITEConfig, VLITEStage)

  val simParams = SimParams(888L, Vector(0.1, -0.2, 1.0, -0.2, 0.1), 6.2, 128)

  val fileParams = Map(
    true -> FileParams("/tmp/test_@STATION@_@THREAD@.dat", true),
    false -> FileParams("/tmp/test_@STATION@_@THREAD@.dat", false)
  )

  val fileStationID = 4

  val fileThreadID = 0

  def testGenerator(
    threadID: Int,
    stationID: Int,
    decimation: Int = decimation,
    pace: FiniteDuration = pace,
    syncOffset: Int = 1,
    genParams: Option[GeneratorParams] = None) = {
    val result = system.actorOf(Generator.props(
      threadID,
      stationID,
      transporter.get,
      decimation = decimation,
      pace = pace,
      arraySize = arraySize,
      genParams = genParams))
    result ! Controller.SyncFramesTo(
      DateTime.now(DateTimeZone.UTC).plusSeconds(syncOffset).
        withMillisOfSecond(0))
    result
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
    scheduler.scheduleOnce(3.seconds, generator, PoisonPill)
    tossFrames(3500.millis)
    expectNoMsg
  }

  it should "generate frames at the desired rate" in {
    import system._
    val generator = testGenerator(0, 0)
    tossFrames(3.seconds)
    scheduler.scheduleOnce(4.seconds, generator, PoisonPill)
    val frames = receiveWhile(5.seconds) {
      case _: GeneratorSpec.Packet => true
    }
    val expectedNumber = 4 * VLITEConfig.framesPerSec / decimation
    val tolerance = expectedNumber / 100
    frames.length should === (expectedNumber +- tolerance)
  }

  it should "generate frames with the provided threadID and stationID" in {
    import system._
    val threadID = 111
    val stationID = 890
    val generator = testGenerator(threadID, stationID)
    val packet = expectMsgClass(classOf[GeneratorSpec.Packet])
    generator ! PoisonPill
    val frame = evtPipe(packet.byteString)._1.head
    frame.header.threadID should === (threadID)
    frame.header.stationID should === (stationID)
  }

  it should "generate simulated frames upon request" in {
    import system._
    val decimation = 512
    val generator =
      testGenerator(0, 0, decimation = decimation, genParams = Some(simParams))
    tossFrames(2.seconds)
    scheduler.scheduleOnce(5.seconds, generator, PoisonPill)
    val frames = receiveWhile(6.seconds) {
      case GeneratorSpec.Packet(bs) => bs
    }
    val dataFrames = frames map { f =>
      evtPipe(f)._1.head
    }
    // val expectedNumber = 5 * VLITEConfig.framesPerSec / decimation
    // val tolerance = expectedNumber / 100
    dataFrames.length should be > 0
  }

  it should "generate frames from file data upon request" in {
    import system._
    val file = fileParams(true).file(fileStationID, fileThreadID)
    val f = new FileOutputStream(file)
    val numBytes = 2 * arraySize
    f.write(((0 until numBytes) map (_.toByte)).toArray)
    f.close()
    try {
      val generator = testGenerator(
        fileStationID,
        fileThreadID,
        genParams = Some(fileParams(true)))
      tossFrames(2.seconds)
      scheduler.scheduleOnce(5.seconds, generator, PoisonPill)
      val frames = receiveWhile(6.seconds) {
        case GeneratorSpec.Packet(bs) => bs
      }
      val dataFrames = frames map { f =>
        evtPipe(f)._1.head
      }
      // val expectedNumber = 5 * VLITEConfig.framesPerSec / decimation
      // val tolerance = expectedNumber / 100
      dataFrames.length should be > 0
    } finally {
      file.delete()
    }
  }

  it should "generate frames from file with expected data" in {
    import system._
    val file = fileParams(true).file(fileStationID, fileThreadID)
    val f = new FileOutputStream(file)
    val numBytes = 2 * arraySize
    f.write(((0 until numBytes) map (_.toByte)).toArray)
    f.close()
    val decimation = 5120
    try {
      val generator = testGenerator(
        fileStationID,
        fileThreadID,
        genParams = Some(fileParams(true)))
      scheduler.scheduleOnce(4.seconds, generator, PoisonPill)
      val frames = receiveWhile(5.seconds) {
        case GeneratorSpec.Packet(bs) => bs
      }
      val dataFrames = frames map { f => evtPipe(f)._1.head }
      def dataArray(i: Int) = {
        val result = new Array[Byte](dataFrames(i).dataArray.length)
        dataFrames(i).dataArray.asByteBuffer.get(result)
        result.toSeq
      }
      def expectedArray(i: Int) = {
        ((i * arraySize) until ((i + 1) * arraySize)) map { n =>
          (n % numBytes).toByte
        }
      }
      for (i <- 0 until dataFrames.length)
        dataArray(i) should contain theSameElementsInOrderAs expectedArray(i)
    } finally {
      file.delete()
    }
  }

  it should "deliver synchronized frames" in {
    import system._
    val generators = for (i <- 0 until 4) yield {
      testGenerator(1, i, syncOffset = 2)
    }
    scheduler.scheduleOnce(4.seconds)(generators foreach (_ ! PoisonPill))
    val frames = receiveWhile(5.seconds) {
      case GeneratorSpec.Packet(bs) => bs
    }
    val frameHeaders = frames map { f => evtPipe(f)._1.head.header }
    for (i <- 0 until 4)
      (frameHeaders exists (_.stationID == i)) shouldBe true
    val framesPerSec = VLITEConfig.framesPerSec / decimation
    val numTolerance = (10 * framesPerSec) / (1.second / pace)
    val closeNums =
      frameHeaders match {
        case first +: rest =>
          ((true, first.secFromRefEpoch, first.numberWithinSec) /: rest) {
            case ((true, sec, num), hdr) =>
              val diff = ((sec - hdr.secFromRefEpoch) * framesPerSec +
                num - hdr.numberWithinSec).abs
              (diff <= numTolerance, hdr.secFromRefEpoch, hdr.numberWithinSec)
            case (acc, _) =>
              acc
          } match {
            case (result, _, _) => result
          }
      }
    closeNums shouldBe true
  }

  it should "deliver frames until file end and then stop" in {
    import system._
    val numExpectedFrames = 10
    val file = fileParams(false).file(fileStationID, fileThreadID)
    val f = new FileOutputStream(file)
    f.write(((0 until numExpectedFrames * arraySize) map (_.toByte)).toArray)
    f.close()
    try {
      val generator = testGenerator(
        fileStationID,
        fileThreadID,
        genParams = Some(fileParams(false)))
      val frames = receiveWhile(2.seconds) {
        case GeneratorSpec.Packet(bs) => bs
      }
      generator ! PoisonPill
      frames should have length numExpectedFrames
    } finally {
      file.delete()
    }
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

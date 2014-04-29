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

import org.scalatest._
import akka.io.{ PipelineFactory, PipelinePorts }
import akka.actor.ActorSystem
import akka.util.ByteString
import scala.math.{ sqrt, pow }
import scala.concurrent.duration._
import akka.testkit.{ ImplicitSender, TestKit }

class VLITESpec(_system: ActorSystem)
    extends TestKit(_system)
    with ImplicitSender
    with FlatSpecLike
    with Matchers {

  def this() = this(ActorSystem("GeneratorSpec"))

  val arraySize = 5000

  object VLITEConfigZeroData extends VLITEConfigZeroData {
    val dataArraySize = arraySize
  }

  // object VLITEConfigSimData extends VLITEConfigSimData {
  //   val seed = 888L
  //   val filter = Vector(0.1, -0.2, 1.0, -0.2, 0.1)
  //   val scale = 6.0
  //   val offset = 128
  //   val numRngThreads = 2
  //   val dataArraySize = arraySize
  // }

  val PipelinePorts(cmdPipeZero, evtPipeZero, _) =
    PipelineFactory.buildFunctionTriple(VLITEConfigZeroData, VLITEStage)

  // val PipelinePorts(cmdPipeSim, evtPipeSim, _) =
  //   PipelineFactory.buildFunctionTriple(VLITEConfigSimData, VLITEStage)

  "A VLITE frame" should
    "encode header to binary and decode it again" in {
      VLITEConfigZeroData.dataArray(1)
      val dataArray = receiveOne(2.seconds) match {
        case ValueSource.Values(vs) =>
          vs.asInstanceOf[Vector[ByteString]].head
      }
      val frame = VLITEFrame(
        VLITEHeader(
          isInvalidData = false,
          secFromRefEpoch = 1000,
          refEpoch = 1,
          numberWithinSec = 50,
          threadID = 1,
          stationID = 6,
          lengthBy8 = (arraySize + 32) / 8),
        dataArray)
      val bs = cmdPipeZero(frame)._2.head
      val frame1 = evtPipeZero(bs)._1.head
      frame1 should === (frame)
  }

  it should "provide zero array data when requested" in {
    VLITEConfigZeroData.dataArray(1)
    val dataArray = receiveOne(2.seconds) match {
      case ValueSource.Values(vs) =>
        vs.asInstanceOf[Vector[ByteString]].head
    }
    val frame = VLITEFrame(
      VLITEHeader(
        isInvalidData = false,
        secFromRefEpoch = 1000,
        refEpoch = 1,
        numberWithinSec = 50,
        threadID = 1,
        stationID = 6,
        lengthBy8 = (arraySize + 32) / 8),
      dataArray)
    val bs = cmdPipeZero(frame)._2.head
    val frame1 = evtPipeZero(bs)._1.head
    all (frame1.dataArray) should === (0)
  }

  // it should "provide random array data when requested" in {
  //   val hdr = VLITEHeader(
  //     isInvalidData = false,
  //     secFromRefEpoch = 1000,
  //     refEpoch = 1,
  //     numberWithinSec = 50,
  //     threadID = 1,
  //     stationID = 6,
  //     lengthBy8 = (arraySize + 32) / 8)
  //   val frame = cmdPipeSim(hdr)._2.head
  //   val (_, array) = evtPipeSim(frame)._1.head
  //   val intArray = array map {
  //     case b if b >= 0 => b.toInt
  //     case b => b.toInt + 256
  //   }
  //   val mean = intArray.sum.toDouble / intArray.length
  //   val rms = sqrt(intArray.map(b => pow(b - mean, 2.0)).sum / array.length)
  //   rms should (be >= (5.0) and be <= (8.0))
  // }
}

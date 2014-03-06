package edu.nrao.vlite

import org.scalatest._
import akka.io.{ PipelineFactory, PipelinePorts }
import scala.math.{ sqrt, pow }

class VLITESpec extends FlatSpec with Matchers {

  val arraySize = 5000

  object VLITEConfigZeroData extends VLITEConfigZeroData {
    val dataArraySize = arraySize
  }

  object VLITEConfigSimData extends VLITEConfigSimData {
    val seed = 888L
    val filter = Vector(0.1, -0.2, 1.0, -0.2, 0.1)
    val scale = 6.0
    val offset = 128.0
    val dataArraySize = arraySize
  }

  val PipelinePorts(cmdPipeZero, evtPipeZero, _) =
    PipelineFactory.buildFunctionTriple(VLITEConfigZeroData, VLITEStage)

  val PipelinePorts(cmdPipeSim, evtPipeSim, _) =
    PipelineFactory.buildFunctionTriple(VLITEConfigSimData, VLITEStage)

  "A VLITE frame" should
    "encode header to binary and decode it again" in {
      val hdr = VLITEHeader(
        isInvalidData = false,
        secFromRefEpoch = 1000,
        refEpoch = 1,
        numberWithinSec = 50,
        threadID = 1,
        stationID = 6,
        lengthBy8 = (arraySize + 32) / 8)
      val frame = cmdPipeZero(hdr)._2.head
      val (hdr1, _) = evtPipeZero(frame)._1.head
      hdr1 should === (hdr)
  }

  it should "provide zero array data when requested" in {
    val hdr = VLITEHeader(
      isInvalidData = false,
      secFromRefEpoch = 1000,
      refEpoch = 1,
      numberWithinSec = 50,
      threadID = 1,
      stationID = 6,
      lengthBy8 = (arraySize + 32) / 8)
    val frame = cmdPipeZero(hdr)._2.head
    val (_, array) = evtPipeZero(frame)._1.head
    all (array) should === (0)
  }

  it should "provide random array data when requested" in {
    val hdr = VLITEHeader(
      isInvalidData = false,
      secFromRefEpoch = 1000,
      refEpoch = 1,
      numberWithinSec = 50,
      threadID = 1,
      stationID = 6,
      lengthBy8 = (arraySize + 32) / 8)
    val frame = cmdPipeSim(hdr)._2.head
    val (_, array) = evtPipeSim(frame)._1.head
    val intArray = array map {
      case b if b >= 0 => b.toInt
      case b => b.toInt + 256
    }
    val mean = intArray.sum.toDouble / intArray.length
    val rms = sqrt(intArray.map(b => pow(b - mean, 2.0)).sum / array.length)
    rms should (be >= (5.0) and be <= (8.0))
  }
}

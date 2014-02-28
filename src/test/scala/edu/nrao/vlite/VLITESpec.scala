package edu.nrao.vlite

import org.scalatest._
import akka.io.{ PipelineFactory, PipelinePorts }

class VLITESpec extends FlatSpec with Matchers {

  val arraySize = 5000

  object VLITEConfig extends VLITEConfig {
    val dataArraySize = arraySize
    lazy val initDataArray = Seq.fill[Byte](dataArraySize)(0)
  }

  val PipelinePorts(vliteCommandPipeline, vliteEventPipeline, _) =
    PipelineFactory.buildFunctionTriple(VLITEConfig, VLITEStage)

  "A VLITE frame" should
    "encode to binary and decode again" in {
      val hdr = VLITEHeader(
        isInvalidData = false,
        secFromRefEpoch = 1000,
        refEpoch = 1,
        numberWithinSec = 50,
        threadID = 1,
        stationID = 6,
        lengthBy8 = (arraySize + 32) / 8)
      val frame = vliteCommandPipeline(hdr)._2.head
      val hdr1 = vliteEventPipeline(frame)._1.head
      hdr1 should === (hdr)
  }
}

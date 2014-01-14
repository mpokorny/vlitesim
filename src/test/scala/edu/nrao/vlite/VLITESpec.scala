package edu.nrao.vlite

import java.nio.ByteBuffer
import org.scalatest._

class VLITESpec extends FlatSpec with Matchers {

  "An VLITE frame" should
    "encode to Ethernet and decode again" in {
      val v = VLITEFrame(VLITEHeader(
        isInvalidData = false,
        secFromRefEpoch = 1000,
        refEpoch = 1,
        numberWithinSec = 50,
        threadID = 1,
        stationID = 6))
      val src = MAC(0,1,2,3,4,5)
      val dst = MAC(10,11,12,13,14,15)
      val eth = Ethernet(source=src, destination=dst, payload=v)
      eth should === (eth.frame.read)
  }

}

package edu.nrao

import java.nio.ByteBuffer

package object vlite {

  implicit object MACBuilder extends FrameBuilder[MAC] {
    val frameSize: Short = 6

    def build(mac: MAC, buffer: ByteBuffer) {
      buffer.put(mac.octet0).
        put(mac.octet1).
        put(mac.octet2).
        put(mac.octet3).
        put(mac.octet4).
        put(mac.octet5)
    }
  }

  implicit class MACReader(buffer: ByteBuffer) extends FrameReader[MAC] {
    def unframe() = {
      val octet0 = buffer.get
      val octet1 = buffer.get
      val octet2 = buffer.get
      val octet3 = buffer.get
      val octet4 = buffer.get
      val octet5 = buffer.get
      MAC(octet0, octet1, octet2, octet3, octet4, octet5)
    }
  }
}

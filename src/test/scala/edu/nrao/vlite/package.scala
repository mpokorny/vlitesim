package edu.nrao.vlite

import java.nio.ByteBuffer

package object test {
  case class TestPayload(val int: Int, val float: Float, val short: Short)
      extends Frame[TestPayload]

  implicit object TestPayloadBuilder extends FrameBuilder[TestPayload] {
    val frameSize: Short = 10

    def build(testPayload: TestPayload, buffer: ByteBuffer) {
      buffer.putInt(testPayload.int)
      buffer.putFloat(testPayload.float)
      buffer.putShort(testPayload.short)
    }
  }

  implicit class TestPayloadReader(buffer: ByteBuffer)
      extends FrameReader[TestPayload] {
    def unframe() = {
      val int = buffer.getInt()
      val float = buffer.getFloat()
      val short = buffer.getShort()
      TestPayload(int, float, short)
    }
  }

  implicit object TestEthernetBuilder extends EthernetBuilder[TestPayload]

  implicit class TestEthernetReader(buffer: ByteBuffer)
      extends EthernetReader[TestPayload](buffer)

}

package edu.nrao.vlite

import java.nio.ByteBuffer
import org.scalatest._

class EthernetFrameSpec extends FlatSpec with Matchers {

  final class TestPayload(val int: Int, val float: Float, val short: Short)
      extends Frame[TestPayload] {
    override def equals(other: Any) = other match {
      case that: TestPayload =>
        that.int == int && that.float == float && that.short == short
      case _ =>
        false
    }

    override def hashCode =
      41 * (
        41 * (
          41 + int
        ) + float.hashCode
      ) + short

    override def toString = s"TestPayload($int,$float,$short)"
  }

  object TestPayload {
    def apply(int: Int, float: Float, short: Short) =
      new TestPayload(int, float, short)

    def unapply(p: TestPayload) =
      Some((p.int, p.float, p.short))

    implicit object PayloadBuilder extends FrameBuilder[TestPayload] {
      val frameSize: Short = 10

      def build(testPayload: TestPayload, buffer: ByteBuffer) {
        buffer.putInt(testPayload.int)
        buffer.putFloat(testPayload.float)
        buffer.putShort(testPayload.short)
      }
    }

    implicit class PayloadReader(buffer: ByteBuffer)
        extends FrameReader[TestPayload] {
      def unframe() = {
        val int = buffer.getInt()
        val float = buffer.getFloat()
        val short = buffer.getShort()
        TestPayload(int, float, short)
      }
    }

    implicit object EthernetBuilder extends Ethernet.Builder[TestPayload]

    implicit class EthernetReader(buffer: ByteBuffer)
        extends Ethernet.Reader[TestPayload](buffer)
  }

  "An Ethernet frame" should
    "encode and decode a test payload" in {
      val t = TestPayload(4, 3.21f, 1)
      val src = MAC(0,1,2,3,4,5)
      val dst = MAC(10,11,12,13,14,15)
      val eth = Ethernet(source=src, destination=dst, payload=t)
      val ethFrame = eth.frame
      ethFrame.position(0)
      eth should === ((new TestPayload.EthernetReader(ethFrame)).unframe)
  }

}

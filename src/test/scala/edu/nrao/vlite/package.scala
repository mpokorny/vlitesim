package edu.nrao.vlite

import java.nio.ByteBuffer
import org.scalatest._

class FrameSpec extends FlatSpec with Matchers {

  final class TestPayload(val int: Int, val float: Float, val short: Short)
      extends Frame[TestPayload] {
    override def equals(other: Any) = other match {
      case TestPayload(tInt, tFloat, tShort) =>
        tInt == int && tFloat == float && tShort == short
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

      def apply(testPayload: TestPayload, buffer: TypedBuffer[TestPayload]) {
        val b = buffer.byteBuffer
        b.putInt(testPayload.int)
        b.putFloat(testPayload.float)
        b.putShort(testPayload.short)
      }
    }

    implicit object PayloadReader extends FrameReader[TestPayload] {
      def apply(buffer: TypedBuffer[TestPayload]) = {
        val b = buffer.byteBuffer
        val int = b.getInt()
        val float = b.getFloat()
        val short = b.getShort()
        TestPayload(int, float, short)
      }
    }

    implicit object EthernetBuilder extends Ethernet.Builder[TestPayload]

    implicit object EthernetReader extends Ethernet.Reader[TestPayload]
  }

  "An Ethernet frame" should
    "encode and decode a test payload" in {
      val t = TestPayload(4, 3.21f, 1)
      val src = MAC(0,1,2,3,4,5)
      val dst = MAC(10,11,12,13,14,15)
      val eth = Ethernet(source=src, destination=dst, payload=t)
      eth should === (eth.frame.read)
  }

}

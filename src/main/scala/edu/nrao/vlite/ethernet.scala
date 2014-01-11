package edu.nrao.vlite

import java.nio.ByteBuffer
//import java.util.zip.CRC32

final case class MAC(
  val octet0: Byte,
  val octet1: Byte,
  val octet2: Byte,
  val octet3: Byte,
  val octet4: Byte,
  val octet5: Byte
) extends Frame[MAC]

final case class Ethernet[T <: Frame[T]](
  val destination: MAC,
  val source: MAC,
  val payload: T
) extends Frame[Ethernet[T]]

class EthernetBuilder[T <: Frame[T]](implicit val tBuilder: FrameBuilder[T])
    extends FrameBuilder[Ethernet[T]] {
  private val overhead: Short = 38

  def macBuilder = implicitly[FrameBuilder[MAC]]

  val frameSize: Short = (overhead + tBuilder.frameSize).toShort

  def build(eth: Ethernet[T], buffer: ByteBuffer) {
    buffer.putLong(0x55555555555555D5L)
    eth.destination.frame(buffer)
    eth.source.frame(buffer)
    buffer.putShort(tBuilder.frameSize)
    eth.payload.frame(buffer)
    buffer.putInt(0)
    buffer.putLong(0)
    buffer.putInt(0)
  }
}

class EthernetReader[T <: Frame[T]](buffer: ByteBuffer)
  (implicit val tReaderFn: (ByteBuffer) => FrameReader[T])
    extends FrameReader[Ethernet[T]] {

  lazy val macReader: FrameReader[MAC] = buffer

  lazy val tReader = tReaderFn(buffer)

  def unframe() = {
    val preambleAndSFD = buffer.getLong()
    val destination = macReader.unframe()
    val source = macReader.unframe()
    val frameSize = buffer.getShort()
    val payload = tReader.unframe()
    val crc = buffer.getInt()
    val gap0 = buffer.getLong()
    val gap1 = buffer.getInt()
    Ethernet(destination, source, payload)
  }
}

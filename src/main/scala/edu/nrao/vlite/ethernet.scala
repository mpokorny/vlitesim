package edu.nrao.vlite

import java.nio.ByteBuffer
//import java.util.zip.CRC32

final class MAC(
  val octet0: Byte,
  val octet1: Byte,
  val octet2: Byte,
  val octet3: Byte,
  val octet4: Byte,
  val octet5: Byte
) extends Frame[MAC] {
  override def equals(other: Any) = other match {
    case that: MAC =>
      that.octet0 == that.octet0 &&
      that.octet1 == that.octet1 &&
      that.octet2 == that.octet2 &&
      that.octet3 == that.octet3 &&
      that.octet4 == that.octet4 &&
      that.octet5 == that.octet5
    case _ => false
  }

  override def hashCode =
    41 * (
      41 * (
        41 * (
          41 * (
            41 * (
              41 + octet0
            ) + octet1
          ) + octet2
        ) + octet3
      ) + octet4
    ) + octet5

  override def toString = s"MAC($octet0,$octet1,$octet2,$octet3,$octet4,$octet5)"
}

object MAC {
  def apply(
    octet0: Byte,
    octet1: Byte,
    octet2: Byte,
    octet3: Byte,
    octet4: Byte,
    octet5: Byte) =
    new MAC(octet0, octet1, octet2, octet3, octet4, octet5)

  def unapply(mac: MAC) = Some(
    (mac.octet0, mac.octet1, mac.octet2, mac.octet3, mac.octet4, mac.octet5))

  implicit object MACBuilder extends FrameBuilder[MAC] {
    val frameSize: Short = 6

    def apply(mac: MAC, buffer: TypedBuffer[MAC]) {
      buffer.byteBuffer.put(mac.octet0).
        put(mac.octet1).
        put(mac.octet2).
        put(mac.octet3).
        put(mac.octet4).
        put(mac.octet5)
    }
  }

  implicit object MACReader extends FrameReader[MAC] {
    def apply(buffer: TypedBuffer[MAC]) = {
      val b = buffer.byteBuffer
      val octet0 = b.get
      val octet1 = b.get
      val octet2 = b.get
      val octet3 = b.get
      val octet4 = b.get
      val octet5 = b.get
      MAC(octet0, octet1, octet2, octet3, octet4, octet5)
    }
  }
}

final class Ethernet[T <: Frame[T]](
  val destination: MAC,
  val source: MAC,
  val payload: T
) extends Frame[Ethernet[T]] {
  override def equals(other: Any) = other match {
    case eth: Ethernet[_] =>
      eth.destination == destination &&
      eth.source == source &&
      eth.payload == payload
    case _ =>
      false
  }

  override def hashCode =
    41 * (41 * (41 + destination.hashCode) + source.hashCode) + payload.hashCode

  override def toString =
    s"Ethernet($destination,$source,$payload)"
}

object Ethernet {
  def apply[T <: Frame[T]](destination: MAC, source: MAC, payload: T) =
    new Ethernet(destination, source, payload)

  def unapply[T <: Frame[T]](eth: Ethernet[T]) =
    Some((eth.destination, eth.source, eth.payload))

  class Builder[T <: Frame[T]](
    implicit val tReader: FrameReader[T],
    val tBuilder: FrameBuilder[T])
      extends FrameBuilder[Ethernet[T]] {
    private val overhead: Short = 38

    val frameSize: Short = (overhead + tBuilder.frameSize).toShort

    def apply(eth: Ethernet[T], buffer: TypedBuffer[Ethernet[T]]) {
      val b = buffer.byteBuffer
      b.putLong(0x55555555555555D5L)
      buffer.slice[MAC].write(eth.destination)
      buffer.slice[MAC].write(eth.source)
      b.putShort(tBuilder.frameSize)
      buffer.slice[T].write(eth.payload)
      b.putInt(0)
      b.putLong(0)
      b.putInt(0)
    }
  }

  class Reader[T <: Frame[T]](
    implicit val tReader: FrameReader[T],
    val tBuilder: FrameBuilder[T])
      extends FrameReader[Ethernet[T]] {

    def apply(buffer: TypedBuffer[Ethernet[T]]) = {
      val b = buffer.byteBuffer
      val preambleAndSFD = b.getLong()
      val destination = buffer.slice[MAC].read
      val source = buffer.slice[MAC].read
      val frameSize = b.getShort()
      val payload = buffer.slice[T].read
      val crc = b.getInt()
      val gap0 = b.getLong()
      val gap1 = b.getInt()
      Ethernet(destination, source, payload)
    }
  }
}

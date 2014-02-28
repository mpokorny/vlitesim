package edu.nrao.vlite

import akka.util.{ ByteString, ByteStringBuilder }
import akka.io._
import java.nio.ByteOrder
import java.net.{ InetAddress, Inet4Address, InetSocketAddress }

final class MAC(
  val octet0: Byte,
  val octet1: Byte,
  val octet2: Byte,
  val octet3: Byte,
  val octet4: Byte,
  val octet5: Byte
) extends Serializable {
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

  override def toString =
    List(octet0, octet1, octet2, octet3, octet4, octet5) map { o =>
      f"$o%02x"
    } mkString(":")
}

object MAC {
  def apply(
    octet0: Byte,
    octet1: Byte,
    octet2: Byte,
    octet3: Byte,
    octet4: Byte,
    octet5: Byte): MAC =
    new MAC(octet0, octet1, octet2, octet3, octet4, octet5)

  def apply(macStr: String): MAC = {
    val octetsStr = macStr.split(':')
    require(
      octetsStr.length == 6,
      "Wrong number of octets in MAC address string: require 6")
    val octets = octetsStr map { o =>
      val s = java.lang.Short.parseShort(o, 16)
      if (0 <= s && s < 0xFF) s.toByte
      else throw new java.lang.NumberFormatException(
        s"Value out of range. Value:$o Radix: 16")
    }
    new MAC(octets(0), octets(1), octets(2), octets(3), octets(4), octets(5))
  }

  def apply(octets: Array[Byte]): MAC = {
    require(octets.length == 6, "Invalid number of octets in byte array")
    new MAC(octets(0), octets(1), octets(2), octets(3), octets(4), octets(5))
  }

  def unapply(mac: MAC) = Some(
    (mac.octet0, mac.octet1, mac.octet2, mac.octet3, mac.octet4, mac.octet5))
}

trait HasEtherCode {
  def etherCode: Short
}

final class Ethernet[T <: HasEtherCode](
  val destination: MAC,
  val source: MAC,
  val payload: T
) {
  override def equals(other: Any) = other match {
    case eth: Ethernet[_] =>
      eth.destination == destination &&
      eth.source == source &&
      eth.payload == payload
    case _ =>
      false
  }

  override def hashCode =
    41 * (
      41 * (
        41 + destination.hashCode
      ) + source.hashCode
    ) + payload.hashCode

  override def toString =
    s"Ethernet($destination,$source,$payload)"
}

object Ethernet {
  def apply[T <: HasEtherCode](destination: MAC, source: MAC, payload: T) =
    new Ethernet(destination, source, payload)

  def unapply[T <: HasEtherCode](eth: Ethernet[T]) =
    Some((eth.destination, eth.source, eth.payload))
}

trait EthernetContext extends PipelineContext {
  def withEthCRC(bs: ByteString)(implicit byteOrder: ByteOrder): ByteString
}

class EthernetStage[C <: EthernetContext, T <: HasEtherCode](
  payloadStage: SymmetricPipelineStage[C, T, ByteString])
    extends SymmetricPipelineStage[C, Ethernet[T], ByteString] {

  protected def putMAC(bb: ByteStringBuilder, mac: MAC): ByteStringBuilder = {
    bb.putBytes(
      Array(mac.octet0, mac.octet1, mac.octet2, mac.octet3, mac.octet4, mac.octet5))
  }

  val minPayload: Short = 46

  override def apply(ctx: C) =
    new SymmetricPipePair[Ethernet[T], ByteString] {

      implicit val byteOrder = ByteOrder.BIG_ENDIAN

      val PipelinePorts(cmdPort, evtPort, _) =
        PipelineFactory.buildFunctionTriple(ctx, payloadStage)

      def commandPipeline = { eth: Ethernet[T] =>
        val bb = ByteString.newBuilder
        putMAC(putMAC(bb, eth.destination), eth.source)
        bb.putShort(eth.payload.etherCode)
        val p = cmdPort(eth.payload)._2.head
        bb ++= p
        val len = p.length
        val pad = (minPayload - len) max 0
        if (pad > 0) bb.putBytes(Array.fill[Byte](pad)(0))
        ctx.singleCommand(ctx.withEthCRC(bb.result))
      }

      def eventPipeline = { bs: ByteString =>
        val iter = bs.iterator
        val macArray = Array.fill[Byte](6)(0)
        iter.getBytes(macArray, 0, 6)
        val destination = MAC(macArray)
        iter.getBytes(macArray, 0, 6)
        val source = MAC(macArray)
        val etherCode = iter.getShort
        val p = evtPort(iter.toByteString)._1.head
        assert(p.etherCode == etherCode)
        ctx.singleEvent(Ethernet(destination, source, p))
      }
    }
}

case class Raw8023Frame(payload: ByteString) extends HasEtherCode {
  def etherCode = payload.length.toShort
}

class Raw8023FrameStage[C <: EthernetContext]
    extends SymmetricPipelineStage[C, Raw8023Frame, ByteString] {

  override def apply(ctx: C) =
    new SymmetricPipePair[Raw8023Frame, ByteString] {
      
      def commandPipeline = { frame: Raw8023Frame =>
        ctx.singleCommand(frame.payload)
      }

      def eventPipeline = { bs: ByteString =>
        ctx.singleEvent(Raw8023Frame(bs))
      }
    }
}

trait Ip4Context extends PipelineContext {
  def ttl: Byte
  def withIp4Checksum(packet: ByteString)(
    implicit byteOrder: ByteOrder): ByteString
}

case class Ip4Frame[T](
  source: Inet4Address,
  destination: Inet4Address,
  payload: T) extends HasEtherCode {
  def etherCode = 0x0800
}

class Ip4FrameStage[C <: Ip4Context, T](
  payloadStage: PipelineStage[C, T, ByteString, (T, Byte), ByteString])
    extends SymmetricPipelineStage[C, Ip4Frame[T], ByteString] {

  val version: Byte = 4
  val ihl: Byte = 5
  val word0bytes01 = Array(((version << 4) | ihl).toByte, 0.toByte)
  val dontFragment: Short = 2
  val word1short1 = dontFragment << 13

  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  override def apply(ctx: C) =
    new SymmetricPipePair[Ip4Frame[T], ByteString] {

      val PipelinePorts(cmdPort, evtPort, _) =
        PipelineFactory.buildFunctionTriple(ctx, payloadStage)

      val ip4Bytes = Array.fill[Byte](4)(0)

      def commandPipeline = { frame: Ip4Frame[T] =>
        val (events, commands) = cmdPort(frame.payload)
        val payload = commands.head
        val bb = ByteString.newBuilder
        bb.
          putBytes(word0bytes01).
          putShort(payload.length + ihl * 8).
          putShort(0).
          putShort(word1short1).
          putByte(ctx.ttl).
          putByte(events.head._2).
          putShort(0).
          putBytes(frame.source.getAddress).
          putBytes(frame.destination.getAddress)
        bb ++= payload
        ctx.singleCommand(ctx.withIp4Checksum(bb.result))
      }

      def eventPipeline = { bs: ByteString =>
        val iter = bs.iterator
        // word 0
        iter.getShort
        val totalLength = iter.getShort
        assert(totalLength == bs.length)
        // word 1
        iter.getInt
        // word 2 (don't validate checksum)
        iter.getInt
        iter.getBytes(ip4Bytes)
        val source = InetAddress.getByAddress(ip4Bytes).asInstanceOf[Inet4Address]
        iter.getBytes(ip4Bytes)
        val destination =
          InetAddress.getByAddress(ip4Bytes).asInstanceOf[Inet4Address]
        val payload = evtPort(iter.toByteString)._1.head._1
        ctx.singleEvent(Ip4Frame(source, destination, payload))
      }
    }
}

case class UdpFrame(
  source: InetSocketAddress,
  destination: InetSocketAddress,
  payload: ByteString)

trait UdpContext extends PipelineContext {
  def withUdpChecksum(
    source: Inet4Address,
    destination: Inet4Address,
    protocol: Byte,
    bs: ByteString)(implicit byteOrder: ByteOrder): ByteString
}

class UdpFrameStage[C <: UdpContext] extends PipelineStage[
  C,
  UdpFrame,
  ByteString,
  (UdpFrame, Byte),
  ByteString] {

  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  val protocol: Byte = 0x11

  private def ipAndPort(address: InetSocketAddress): (Inet4Address, Short) =
    (address.getAddress.asInstanceOf[Inet4Address], address.getPort.toShort)

  override def apply(ctx: C) =
    new PipePair[UdpFrame, ByteString, (UdpFrame, Byte), ByteString] {

      def commandPipeline = { frame: UdpFrame =>
        val (srcIp, srcPort) = ipAndPort(frame.source)
        val (dstIp, dstPort) = ipAndPort(frame.destination)
        val bb = ByteString.newBuilder
        bb.
          putShort(srcPort).
          putShort(dstPort).
          putShort(frame.payload.length + 8).
          putShort(0)
        bb ++= frame.payload
        List(
          Right(ctx.withUdpChecksum(srcIp, dstIp, protocol, bb.result)),
          Left(frame, protocol))
      }

      def eventPipeline = { bs: ByteString =>
        val iter = bs.iterator
        val source = iter.getShort
        val destination = iter.getShort
        val length = iter.getShort
        val checksum = iter.getShort
        val payload = iter.toByteString
        assert(payload.length + 8 == length)
        ctx.singleEvent((
          UdpFrame(
            InetSocketAddress.createUnresolved("0.0.0.0", source),
            InetSocketAddress.createUnresolved("0.0.0.0", destination),
            payload),
          protocol))
      }
    }
}

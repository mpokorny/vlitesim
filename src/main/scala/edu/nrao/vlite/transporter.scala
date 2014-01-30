package edu.nrao.vlite

import akka.actor.{ Actor, ActorRef, ActorLogging, Props, Terminated }
import akka.util.ByteString
import akka.io._
import java.net.{ InetAddress, Inet4Address, InetSocketAddress }
import java.nio.ByteOrder

object Transporter {
  case class Transport(byteString: ByteString)
  case object GetBufferCount
  case class BufferCount(count: Long)
  case class OpenWarning(message: String)
  case class OpenException(cause: String) extends Exception(cause)
}

trait Transporter extends Actor with ActorLogging {
  import Transporter._

  protected var bufferCount: Long = 0L

  def receive: Receive = {
    case Transport(byteString) =>
      if (send(byteString)) {
        if (bufferCount < Long.MaxValue) bufferCount += 1
        else bufferCount = 0
      }
    case GetBufferCount =>
      sender ! BufferCount(bufferCount)
  }

  protected def send(byteString: ByteString): Boolean
}

abstract class EthernetTransporter[C <: EthernetContext, T <: HasEtherCode](
  val device: String,
  val dst: MAC)
    extends Transporter {
  import edu.nrao.vlite.pcap._
  import Transporter._
  import EthernetTransporter._
  import org.jnetpcap.Pcap

  protected var pcap: Pcap = null

  protected var src: MAC = null

  protected val pipelineStage: EthernetStage[C, T]

  protected val pipelineContext: C

  private val PipelinePorts(pipelinePort, _, _) =
    PipelineFactory.buildFunctionTriple(pipelineContext, pipelineStage)

  private def toBinary(eth: Ethernet[T]): ByteString =
    pipelinePort(eth)._2.head

  protected def ethFrame(bs: ByteString): Ethernet[T]


  override def preStart() {
    val errbuff = new java.lang.StringBuilder("")
    pcap =
      Pcap.openLive(device, 0, Pcap.MODE_NON_PROMISCUOUS, 5 * 1000, errbuff)
    if (pcap == null) {
      val cause = if (errbuff.length > 0) errbuff.toString else "unknown cause"
      throw new OpenException(s"Failed to open device '$device': $cause")
    } else if (errbuff.length > 0) {
      context.parent ! OpenWarning(errbuff.toString)
    }
    val mtu = new java.io.BufferedReader(
      new java.io.FileReader(s"/sys/class/net/$device/mtu")).readLine.toInt
    if (mtu < 9000)
      context.parent ! OpenWarning(s"'$device' MTU is small ($mtu)")
    src = getMAC(device).get
  }

  protected lazy val macs = List(
    dst.octet0, dst.octet1, dst.octet2, dst.octet3, dst.octet4, dst.octet5,
    src.octet0, src.octet1, src.octet2, src.octet3, src.octet4, src.octet5).
    toArray
  
  protected def send(bs: ByteString) = {
    pcap.sendPacket(toBinary(ethFrame(bs))) == 0
  }

  override def toString = s"EthernetTransporter($device)"
}

object RawEthernetContext extends EthernetContext {
  def withEthCRC(bs: ByteString)(implicit byteOrder: ByteOrder): ByteString =
    bs
}

class RawEthernetTransporter(device: String, dst: MAC)
    extends EthernetTransporter[RawEthernetContext.type,Raw8023Frame](
  device, dst) {

  object Raw8023FrameStage extends Raw8023FrameStage[RawEthernetContext.type]

  protected val pipelineStage = new EthernetStage(Raw8023FrameStage)

  protected val pipelineContext = RawEthernetContext

  protected def ethFrame(bs: ByteString) =
    Ethernet(dst, src, Raw8023Frame(bs))

  override def toString = s"RawEthernetTransporter($device)"
}

object UdpEthernetContext extends EthernetContext with Ip4Context with UdpContext {

  def withEthCRC(bs: ByteString)(implicit byteOrder: ByteOrder): ByteString =
    bs

  def ttl: Byte = 2

  def withIp4Checksum(packet: ByteString)(
    implicit byteOrder: ByteOrder): ByteString = packet

  def withUdpChecksum(
    source: Inet4Address,
    destination: Inet4Address,
    protocol: Byte,
    bs: ByteString)(implicit byteOrder: ByteOrder): ByteString =
    bs
}

class UdpEthernetTransporter(device: String, dst: MAC, dstSock: InetSocketAddress)
    extends EthernetTransporter[UdpEthernetContext.type,Ip4Frame[UdpFrame]](
  device, dst) {

  object UdpFrameStage extends UdpFrameStage[UdpEthernetContext.type]

  object Ip4UdpFrameStage extends Ip4FrameStage(UdpFrameStage)

  protected val pipelineStage = new EthernetStage(Ip4UdpFrameStage)

  protected val pipelineContext = UdpEthernetContext

  protected val srcSock = InetSocketAddress.createUnresolved("0.0.0.0", 0)
  protected val srcIP = srcSock.getAddress.asInstanceOf[Inet4Address]
  protected val dstIP = dstSock.getAddress.asInstanceOf[Inet4Address]

  protected def ethFrame(bs: ByteString) =
    Ethernet(dst, src, Ip4Frame(srcIP, dstIP, UdpFrame(srcSock, dstSock, bs)))

  override def toString = s"RawEthernetTransporter($device)"
}

object EthernetTransporter {

  object Framing extends Enumeration {
    type Framing = Value
    val Raw, UDP = Value
  }
  
  def props(
    device: String,
    sockaddr: Option[InetSocketAddress],
    mac: MAC,
    framing: Framing.Framing): Props =
    framing match {
      case Framing.Raw =>
        Props(classOf[RawEthernetTransporter], device, mac)
      case Framing.UDP =>
        Props(classOf[UdpEthernetTransporter], device, mac, sockaddr.get)
    }
}

final class UdpTransporter(val dst: InetSocketAddress)
    extends Transporter {
  import Transporter._
  import java.nio.channels.DatagramChannel
  import context._

  protected var channel: Option[DatagramChannel] = None

  override def preStart() {
    channel = try {
      Some(DatagramChannel.open().connect(dst))
    } catch {
      case e: Exception =>
        parent ! OpenException(e.getMessage)
        None
    }
  }

  override def postStop() {
    channel foreach (_.disconnect())
  }

  protected def send(byteString: ByteString) = {
    (channel.map { ch =>
      val b = byteString.compact.asByteBuffer
      ch.write(b) == b.limit
    }).getOrElse(false)
  }

  override def toString = s"UdpTransporter($dst)"
}

object UdpTransporter {
  def props(dst: InetSocketAddress): Props =
    Props(classOf[UdpTransporter], dst)
}

//
// Copyright © 2014 Associated Universities, Inc. Washington DC, USA.
//
// This file is part of vlitesim.
//
// vlitesim is free software: you can redistribute it and/or modify it under the
// terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version.
//
// vlitesim is distributed in the hope that it will be useful, but WITHOUT ANY
// WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
// A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along with
// vlitesim.  If not, see <http://www.gnu.org/licenses/>.
//
package edu.nrao.vlite

import scala.concurrent.{ Await, Future, ExecutionContext }
import scala.concurrent.duration._
import scala.util.{ Success, Failure }
import akka.actor._
import akka.pattern.AskTimeoutException
import akka.util.ByteString
import akka.io._
import java.net.{ InetAddress, Inet4Address, InetSocketAddress }
import java.nio.ByteOrder

object Transporter {
  case class Transport(byteStrings: Vector[Future[ByteString]])
  case object GetBufferCount
  case class BufferCount(count: Long)
  case class OpenWarning(message: String)
  case class OpenException(cause: String) extends Exception(cause)
}

private case object IncrementBufferCount

abstract class ByteStringsSender extends Actor with ActorLogging {
  import context._

  protected def send(byteString: ByteString): Boolean

  var bsQueue: Vector[Future[ByteString]] = Vector.empty

  case object SendNext

  def receive: Receive = {
    case Transporter.Transport(futureByteStrings) =>
      if (futureByteStrings.length > 0) {
        if (bsQueue.isEmpty) self ! SendNext
        bsQueue ++= futureByteStrings
      }
    case SendNext =>
      bsQueue match {
        case (head +: tail) =>
          bsQueue = tail
          val haveNext = !bsQueue.isEmpty
          val prt = parent
          head onComplete {
            case Success(bs) =>
              if (send(bs)) prt ! IncrementBufferCount
              if (haveNext) self ! SendNext
            case f @ Failure(_) =>
              self ! f
          }
      }
    case Failure(th) =>
      throw th
  }
}

class Transporter(senderProps: Props) extends Actor with ActorLogging {
  import Transporter._
  import context._

  override val supervisorStrategy = OneForOneStrategy() {
    case _: AskTimeoutException => SupervisorStrategy.Escalate
  }

  protected var bufferCount: Long = 0L

  val byteStringsSender = actorOf(senderProps, "sender")

  def receive: Receive = {
    case t: Transport =>
      byteStringsSender ! t
    case IncrementBufferCount =>
      if (bufferCount < Long.MaxValue) bufferCount += 1
      else bufferCount = 0
    case GetBufferCount =>
      sender ! BufferCount(bufferCount)
  }
}

abstract class EthernetSender[T <: HasEtherCode](
  val device: String,
  val dst: MAC)
    extends ByteStringsSender {
  import edu.nrao.vlite.pcap._
  import Transporter._
  import EthernetTransporter._
  import org.jnetpcap.Pcap

  type PC <: EthernetContext

  protected var pcap: Pcap = null

  protected var src: MAC = null

  protected val pipelineStage: EthernetStage[PC, T]

  protected val pipelineContext: PC

  private lazy val PipelinePorts(pipelinePort, _, _) =
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
  
  protected def send(bs: ByteString) =
    pcap.sendPacket(toBinary(ethFrame(bs))) == 0
}

object RawEthernetContext extends EthernetContext {
  def withEthCRC(bs: ByteString)(implicit byteOrder: ByteOrder): ByteString =
    bs
}

class RawEthernetSender(device: String, dst: MAC)
    extends EthernetSender[Raw8023Frame](device, dst) {

  type PC = RawEthernetContext.type

  object Raw8023FrameStage extends Raw8023FrameStage[PC]

  protected val pipelineStage = new EthernetStage(Raw8023FrameStage)

  protected val pipelineContext = RawEthernetContext

  protected def ethFrame(bs: ByteString) =
    Ethernet(dst, src, Raw8023Frame(bs))
}

object UdpEthernetContext
    extends EthernetContext with Ip4Context with UdpContext {

  def withEthCRC(bs: ByteString)(implicit byteOrder: ByteOrder): ByteString =
    bs

  def ttl: Byte = 8

  def withIp4Checksum(packet: ByteString)(
    implicit byteOrder: ByteOrder): ByteString = {
    var sum = 0L
    val iter = packet.slice(0, 20).iterator
    while (iter.hasNext) { sum += iter.getLongPart(2) }
    val sum1 = (sum & 0xFFFF) + ((sum >> 16) & 0xFFFF)
    val checksum = {
      val sum2 = (sum1 & 0xFFFF) + ((sum1 >> 16) & 0xFFFF)
      ByteString.newBuilder.putLongPart(~sum2, 2).result
    }
    packet.slice(0, 10) ++ checksum ++ packet.slice(12, packet.length)
  }

  def withUdpChecksum(
    source: Inet4Address,
    destination: Inet4Address,
    protocol: Byte,
    bs: ByteString)(implicit byteOrder: ByteOrder): ByteString = {

    def addressSum(addr: Inet4Address): Long = {
      val iter = ByteString(addr.getAddress).iterator
      iter.getLongPart(2) + iter.getLongPart(2)
    }
    val udpHeaderSum: Long = {
      val iter = bs.slice(0, 6).iterator
      var result = 0L
      while (iter.hasNext) { result += iter.getLongPart(2) }
      result
    }
    val pseudoHeaderSum = (addressSum(source) + addressSum(destination) +
      ByteString(Array[Byte](0, protocol)).iterator.getShort.toLong +
      bs.length + udpHeaderSum)
    val payloadSum = {
      val buff = if (bs.length % 2 == 0) bs else bs ++ ByteString(0)
      val payload = buff.slice(8, buff.length).iterator
      var result = 0L
      while (payload.hasNext) { result += payload.getLongPart(2) }
      result
    }
    val sum = pseudoHeaderSum + payloadSum
    val sum1 = (sum & 0xFFFF) + ((sum >> 16) & 0xFFFF)
    val checksum = {
      val sum2 = ~((sum1 & 0xFFFF) + ((sum1 >> 16) & 0xFFFF))
      ByteString.newBuilder.putLongPart(if (sum2 != 0) sum2 else ~sum2, 2).result
    }
    bs.slice(0, 6) ++ checksum ++ bs.slice(8, bs.length)
  }
}

class UdpEthernetSender(
  device: String,
  dst: MAC,
  dstSock: InetSocketAddress,
  srcSock: InetSocketAddress)
    extends EthernetSender[Ip4Frame[UdpFrame]](device, dst) {

  type PC = UdpEthernetContext.type

  object UdpFrameStage extends UdpFrameStage[PC]

  object Ip4UdpFrameStage extends Ip4FrameStage(UdpFrameStage)

  protected val pipelineStage = new EthernetStage(Ip4UdpFrameStage)

  protected val pipelineContext = UdpEthernetContext

  protected val srcIP = srcSock.getAddress.asInstanceOf[Inet4Address]
  protected val dstIP = dstSock.getAddress.asInstanceOf[Inet4Address]

  protected def ethFrame(bs: ByteString) =
    Ethernet(dst, src, Ip4Frame(srcIP, dstIP, UdpFrame(srcSock, dstSock, bs)))
}

object EthernetTransporter {

  object Framing extends Enumeration {
    type Framing = Value
    val Raw, UDP = Value
  }
  
  def props(
    device: String,
    mac: MAC,
    dstSock: Option[InetSocketAddress],
    srcSock: Option[InetSocketAddress],
    framing: Framing.Framing): Props =
    framing match {
      case Framing.Raw =>
        Props(classOf[Transporter],
          Props(classOf[RawEthernetSender], device, mac))
      case Framing.UDP =>
        Props(classOf[Transporter],
          Props(
            classOf[UdpEthernetSender],
            device,
            mac,
            dstSock.get,
            srcSock.get))
    }
}

class UdpSender(val dst: InetSocketAddress)
    extends ByteStringsSender {
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

  override def preRestart(reason: Throwable, message: Option[Any]) {
    channel foreach (_.disconnect())
    channel = None
    super.preRestart(reason, message)
  }

  protected def send(byteString: ByteString) = {
    (channel.map { ch =>
      val b = byteString.asByteBuffer
      ch.write(b) == b.limit
    }).getOrElse(false)
  }
}

object UdpTransporter {
  def props(dst: InetSocketAddress): Props =
    Props(classOf[Transporter], Props(classOf[UdpSender], dst))
}

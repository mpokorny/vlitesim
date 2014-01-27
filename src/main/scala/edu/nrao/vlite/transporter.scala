package edu.nrao.vlite

import akka.actor.{ Actor, ActorRef, ActorLogging, Props, Terminated }
import java.net.InetSocketAddress

object Transporter {
  case object Start
  case object Stop
  case class Transport[T <: Frame[T]](buffer: TypedBuffer[Ethernet[T]])
  case object GetBufferCount
  case class BufferCount(count: Long)
  case class OpenWarning(message: String)
  case class OpenException(cause: String) extends Exception(cause)
  case class PreviousState(bufferCount: Long, wasRunning: Boolean)
}

trait Transporter extends Actor with ActorLogging {
  import Transporter._

  protected var bufferCount: Long = 0L

  protected var isRunning: Boolean = false

  protected var runningStateWasSet: Boolean = false

  override def preRestart(reason: Throwable, message: Option[Any]) {
    self ! PreviousState(bufferCount, isRunning)
    super.preRestart(reason, message)
  }

  protected def addToBufferCount(count: Long) {
    if (Long.MaxValue - count >= bufferCount)
      bufferCount += count
    else
      bufferCount = bufferCount - 1 - (Long.MaxValue - count)
  }

  def receive = idle

  def idle: Receive = {
    log.info("idle")
    isRunning = false
    getBufferCount orElse {
      case Start =>
        runningStateWasSet = true
        context.become(transporting)
      case PreviousState(count, wasRunning) =>
        addToBufferCount(count)
        if (!runningStateWasSet && wasRunning)
          context.become(transporting)
      case Transport(_) =>
    }
  }

  def transporting: Receive = {
    log.info("transporting")
    isRunning = true
    transport orElse getBufferCount orElse {
      case Stop =>
        runningStateWasSet = true
        context.become(idle)
      case PreviousState(count, wasRunning) =>
        addToBufferCount(count)
        if (!runningStateWasSet && !wasRunning)
          context.become(idle)
    }
  }

  protected def getBufferCount: Receive = {
    case GetBufferCount =>
      sender ! BufferCount(bufferCount)
  }

  protected def transport: Receive = {
    case Transport(buffer) =>
      if (sendBuffer(buffer)) {
        if (bufferCount < Long.MaxValue) bufferCount += 1
        else bufferCount = 0
      }
  }

  protected def sendBuffer(buffer: TypedBuffer[_]): Boolean
}

final class EthernetTransporter(val device: String, val dst: MAC)
    extends Transporter {
  import edu.nrao.vlite.pcap._
  import Transporter._
  import EthernetTransporter._
  import org.jnetpcap.Pcap

  protected var pcap: Pcap = null

  protected var src: MAC = null

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
  
  protected def sendBuffer(buffer: TypedBuffer[_]) = {
    buffer.byteBuffer.rewind
    buffer.byteBuffer.put(macs)
    pcap.sendPacket(buffer) == 0
  }

  override def toString = s"EthernetTransporter($device)"
}

object EthernetTransporter {
  def props(device: String, dst: MAC): Props =
    Props(classOf[EthernetTransporter], device, dst)
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

  protected def sendBuffer(buffer: TypedBuffer[_]) = {
    (channel.map { ch =>
      val b = buffer.byteBuffer
      b.rewind
      try {
        ch.write(b) == b.limit
      } catch {
        case _: java.net.PortUnreachableException =>
          throw new OpenException(s"Destination port unreachable at $dst")
      }
    }).getOrElse(false)
  }

  override def toString = s"UdpTransporter($dst)"
}

object UdpTransporter {
  def props(dst: InetSocketAddress): Props =
    Props(classOf[UdpTransporter], dst)
}

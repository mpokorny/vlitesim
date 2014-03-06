package edu.nrao.vlite

import akka.actor._
import akka.util.{ ByteString, ByteStringBuilder }

class ByteStringSource(
  val byteSourceProps: Props,
  val length: Int,
  val bufferSize: Int)
    extends ValueSourceBase[ByteString] {

  import context._

  val byteSource = actorOf(byteSourceProps)
  
  def requestValue() {
    byteSource ! ValueSource.Get
  }

  var builder: Option[ByteStringBuilder] = None

  def receiveValue(a: Any): List[ByteString] = {
    if (!builder.isDefined) {
      val b = ByteString.newBuilder
      b.sizeHint(length)
      builder = Some(b)
    }
    val bld = builder.get
    bld.putByte(a.asInstanceOf[Byte])
    if (bld.length == length) {
      builder = None
      List(bld.result)
    } else {
      Nil
    }
  }
}

object ByteStringSource {
  def props(byteSourceProps: Props, length: Int, bufferSize: Int = 1) =
    Props(classOf[ByteStringSource], byteSourceProps, length, bufferSize)
}

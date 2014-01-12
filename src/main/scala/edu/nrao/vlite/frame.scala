package edu.nrao.vlite

import java.nio.ByteBuffer

trait FrameBuilder[T] {
  val frameSize: Short
  def apply(obj: T, buffer: TypedBuffer[T]): Unit
}

trait FrameReader[T] {
  def apply(buffer: TypedBuffer[T]): T
}

trait Frame[T] {
  self: T =>

  def frame(buffer: TypedBuffer[T]) {
    buffer.write(this)
  }

  def frame(implicit reader: FrameReader[T], builder: FrameBuilder[T]):
      TypedBuffer[T] = {
    val result = new TypedBuffer(ByteBuffer.allocate(builder.frameSize))
    result.write(this)
    result
  }
}

class TypedBuffer[T](val byteBuffer: ByteBuffer)
  (implicit reader: FrameReader[T], builder: FrameBuilder[T]) {
  def read: T = {
    byteBuffer.position(0)
    reader(this)
  }
  def write(obj: T) {
    byteBuffer.position(0)
    builder(obj, this)
  }
  def slice[S](implicit sRead: FrameReader[S], sBuild: FrameBuilder[S]):
      TypedBuffer[S] = {
    val sBuffer = byteBuffer.slice
    sBuffer.limit(sBuild.frameSize)
    byteBuffer.position(byteBuffer.position + sBuild.frameSize)
    new TypedBuffer(sBuffer)
  }
}

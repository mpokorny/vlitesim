package edu.nrao.vlite

import java.nio.ByteBuffer

trait FrameBuilder[T] {
  val frameSize: Short
  def build(obj: T, buffer: ByteBuffer): Unit
}

trait FrameReader[T] {
  def unframe(): T
}

trait Frame[T] {
  self: T =>

  def frame(buffer: ByteBuffer)(implicit builder: FrameBuilder[T]) {
    builder.build(self, buffer)
  }

  def frame(implicit builder: FrameBuilder[T]): ByteBuffer = {
    val result = ByteBuffer.allocate(builder.frameSize)
    builder.build(self, result)
    result
  }
}

trait TypedBuffer[T] extends ByteBuffer {
  def read: T
  def write(obj: T): Unit
}

/*
 * Copyright 2014–2016 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ygg.data

import ygg.common._
import java.util.concurrent.ConcurrentHashMap

/**
  * This object contains some methods to do faster iteration over primitives.
  *
  * In particular it doesn't box, allocate intermediate objects, or use a (slow)
  * shared interface with scala collections.
  */
object Loop {
  @tailrec
  def range(i: Int, limit: Int)(f: Int => Unit): Unit = {
    if (i < limit) {
      f(i)
      range(i + 1, limit)(f)
    }
  }

  final def forall[@spec A](as: Array[A])(f: A => Boolean): Boolean = {
    @tailrec def loop(i: Int): Boolean = i == as.length || f(as(i)) && loop(i + 1)

    loop(0)
  }
}

final class LazyMap[A, B, C](source: Map[A, B], f: B => C) extends Map[A, C] {
  private[this] val m = new ConcurrentHashMap[A, C]()

  def iterator: scIterator[A -> C] = source.keysIterator map (a => a -> apply(a))

  def get(a: A): Option[C] = m get a match {
    case null =>
      source get a map { b =>
        val c = f(b)
        m.putIfAbsent(a, c)
        c
      }
    case x => Some(x)
  }

  def +[C1 >: C](kv: (A, C1)): Map[A, C1] = iterator.toMap + kv
  def -(a: A): Map[A, C]                  = iterator.toMap - a
}

final class AtomicIntIdSource[A](f: Int => A) {
  private val source         = new AtomicInt
  def nextId(): A            = f(source.getAndIncrement)
  def nextIdBlock(n: Int): A = f(source.getAndAdd(n + 1) - n)
}

final class AtomicLongIdSource {
  private val source             = new AtomicLong
  def nextId(): Long             = source.getAndIncrement
  def nextIdBlock(n: Long): Long = source.getAndAdd(n + 1) - n
}

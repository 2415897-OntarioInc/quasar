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

package quasar.physical.marklogic.xquery

import quasar.Predef._
import quasar.physical.marklogic.xml.QName

import scalaz._, Id.Id
import scalaz.std.tuple._
import scalaz.syntax.foldable._
import scalaz.syntax.functor._

sealed abstract class FunctionDecl {
  def name: QName
  def parameters: NonEmptyList[TypedBinding]
  def returnType: SequenceType
  def body: XQuery

  def arity: Int =
    parameters.length

  // TODO: Naming?
  def render: String = {
    val paramsList = parameters.map(_.render).toList
    s"declare function ${name}${paramsList.mkString("(", ", ", ")")} as $returnType {\n  $body\n}"
  }
}

object FunctionDecl {
  // TODO: Maybe just name? Until we have namespaces?
  implicit val order: Order[FunctionDecl] =
    Order.orderBy(fd => (fd.name, fd.parameters, fd.returnType))

  implicit val show: Show[FunctionDecl] =
    Show.shows(fd => s"FunctionDecl(${fd.name})")

  final case class FunctionDecl1(
    name: QName,
    param1: TypedBinding,
    returnType: SequenceType,
    body: XQuery
  ) extends FunctionDecl {
    def parameters = NonEmptyList(param1)
  }

  final case class FunctionDecl2(
    name: QName,
    param1: TypedBinding,
    param2: TypedBinding,
    returnType: SequenceType,
    body: XQuery
  ) extends FunctionDecl {
    def parameters = NonEmptyList(param1, param2)
  }

  final case class FunctionDecl3(
    name: QName,
    param1: TypedBinding,
    param2: TypedBinding,
    param3: TypedBinding,
    returnType: SequenceType,
    body: XQuery
  ) extends FunctionDecl {
    def parameters = NonEmptyList(param1, param2, param3)
  }

  final case class FunctionDecl4(
    name: QName,
    param1: TypedBinding,
    param2: TypedBinding,
    param3: TypedBinding,
    param4: TypedBinding,
    returnType: SequenceType,
    body: XQuery
  ) extends FunctionDecl {
    def parameters = NonEmptyList(param1, param2, param3, param4)
  }

  final case class FunctionDecl5(
    name: QName,
    param1: TypedBinding,
    param2: TypedBinding,
    param3: TypedBinding,
    param4: TypedBinding,
    param5: TypedBinding,
    returnType: SequenceType,
    body: XQuery
  ) extends FunctionDecl {
    def parameters = NonEmptyList(param1, param2, param3, param4, param5)
  }

  final case class FunctionDeclDsl(fname: QName) {
    def apply(p1: TypedBinding): FunctionDecl1Dsl =
      FunctionDecl1Dsl(fname, p1, SequenceType.Top)

    def apply(p1: TypedBinding, p2: TypedBinding): FunctionDecl2Dsl =
      FunctionDecl2Dsl(fname, p1, p2, SequenceType.Top)

    def apply(p1: TypedBinding, p2: TypedBinding, p3: TypedBinding): FunctionDecl3Dsl =
      FunctionDecl3Dsl(fname, p1, p2, p3, SequenceType.Top)

    def apply(p1: TypedBinding, p2: TypedBinding, p3: TypedBinding, p4: TypedBinding): FunctionDecl4Dsl =
      FunctionDecl4Dsl(fname, p1, p2, p3, p4, SequenceType.Top)

    def apply(p1: TypedBinding, p2: TypedBinding, p3: TypedBinding, p4: TypedBinding, p5: TypedBinding): FunctionDecl5Dsl =
      FunctionDecl5Dsl(fname, p1, p2, p3, p4, p5, SequenceType.Top)
  }

  final case class FunctionDecl1Dsl(fn: QName, p1: TypedBinding, rt: SequenceType) {
    def as(rType: SequenceType): FunctionDecl1Dsl = copy(rt = rType)

    def apply[F[_]: Functor](body: XQuery => F[XQuery]): F[FunctionDecl1] =
      body(p1.name.xqy) map (FunctionDecl1(fn, p1, rt, _))

    def apply(body: XQuery => XQuery): FunctionDecl1 =
      apply[Id](body)
  }

  final case class FunctionDecl2Dsl(fn: QName, p1: TypedBinding, p2: TypedBinding, rt: SequenceType) {
    def as(rType: SequenceType): FunctionDecl2Dsl = copy(rt = rType)

    def apply[F[_]: Functor](body: (XQuery, XQuery) => F[XQuery]): F[FunctionDecl2] =
      body(p1.name.xqy, p2.name.xqy) map (FunctionDecl2(fn, p1, p2, rt, _))

    def apply(body: (XQuery, XQuery) => XQuery): FunctionDecl2 =
      apply[Id](body)
  }

  final case class FunctionDecl3Dsl(fn: QName, p1: TypedBinding, p2: TypedBinding, p3: TypedBinding, rt: SequenceType) {
    def as(rType: SequenceType): FunctionDecl3Dsl = copy(rt = rType)

    def apply[F[_]: Functor](body: (XQuery, XQuery, XQuery) => F[XQuery]): F[FunctionDecl3] =
      body(p1.name.xqy, p2.name.xqy, p3.name.xqy) map (FunctionDecl3(fn, p1, p2, p3, rt, _))

    def apply(body: (XQuery, XQuery, XQuery) => XQuery): FunctionDecl3 =
      apply[Id](body)
  }

  final case class FunctionDecl4Dsl(fn: QName, p1: TypedBinding, p2: TypedBinding, p3: TypedBinding, p4: TypedBinding, rt: SequenceType) {
    def as(rType: SequenceType): FunctionDecl4Dsl = copy(rt = rType)

    def apply[F[_]: Functor](body: (XQuery, XQuery, XQuery, XQuery) => F[XQuery]): F[FunctionDecl4] =
      body(p1.name.xqy, p2.name.xqy, p3.name.xqy, p4.name.xqy) map (FunctionDecl4(fn, p1, p2, p3, p4, rt, _))

    def apply(body: (XQuery, XQuery, XQuery, XQuery) => XQuery): FunctionDecl4 =
      apply[Id](body)
  }

  final case class FunctionDecl5Dsl(fn: QName, p1: TypedBinding, p2: TypedBinding, p3: TypedBinding, p4: TypedBinding, p5: TypedBinding, rt: SequenceType) {
    def as(rType: SequenceType): FunctionDecl5Dsl = copy(rt = rType)

    def apply[F[_]: Functor](body: (XQuery, XQuery, XQuery, XQuery, XQuery) => F[XQuery]): F[FunctionDecl5] =
      body(p1.name.xqy, p2.name.xqy, p3.name.xqy, p4.name.xqy, p5.name.xqy) map (FunctionDecl5(fn, p1, p2, p3, p4, p5, rt, _))

    def apply(body: (XQuery, XQuery, XQuery, XQuery, XQuery) => XQuery): FunctionDecl5 =
      apply[Id](body)
  }
}

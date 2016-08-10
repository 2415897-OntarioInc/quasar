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

package quasar.physical.mongodb

import quasar.Predef._
import quasar._, Planner._, Type.{Const => _, Coproduct => _, _}
import quasar.fp._
import quasar.fs.QueryFile
import quasar.javascript._
import quasar.jscore, jscore.{JsCore, JsFn}
import quasar.namegen._
import quasar.physical.mongodb.Workflow._
import quasar.physical.mongodb.WorkflowBuilder._
import quasar.qscript._
import quasar.std.StdLib.string._ // TODO: remove this
import javascript._

import matryoshka._, Recursive.ops._, TraverseT.ops._
import scalaz._, Scalaz._

object MongoDbQScriptPlanner {
  type Partial[In, Out] = (PartialFunction[List[In], Out], List[InputFinder])

  type OutputM[A] = PlannerError \/ A

  type PartialJs = Partial[JsFn, JsFn]

  def generateTypeCheck[In, Out](or: (Out, Out) => Out)(f: PartialFunction[Type, In => Out]):
      Type => Option[In => Out] =
        typ => f.lift(typ).fold(
          typ match {
            case Type.Interval => generateTypeCheck(or)(f)(Type.Dec)
            case Type.Arr(_) => generateTypeCheck(or)(f)(Type.AnyArray)
            case Type.Timestamp
               | Type.Timestamp ⨿ Type.Date
               | Type.Timestamp ⨿ Type.Date ⨿ Type.Time =>
              generateTypeCheck(or)(f)(Type.Date)
            case Type.Timestamp ⨿ Type.Date ⨿ Type.Time ⨿ Type.Interval =>
              // Just repartition to match the right cases
              generateTypeCheck(or)(f)(Type.Interval ⨿ Type.Date)
            case Type.Int ⨿ Type.Dec ⨿ Type.Interval ⨿ Type.Str ⨿ (Type.Timestamp ⨿ Type.Date ⨿ Type.Time) ⨿ Type.Bool =>
              // Just repartition to match the right cases
              generateTypeCheck(or)(f)(
                Type.Int ⨿ Type.Dec ⨿ Type.Interval ⨿ Type.Str ⨿ (Type.Date ⨿ Type.Bool))
            case a ⨿ b =>
              (generateTypeCheck(or)(f)(a) ⊛ generateTypeCheck(or)(f)(b))(
                (a, b) => ((expr: In) => or(a(expr), b(expr))))
            case _ => None
          })(
          Some(_))

  def processMapFunc[T[_[_]]: Recursive: ShowT, A](
    fm: Free[MapFunc[T, ?],  A])(
    recovery: A => JsFn):
      OutputM[JsFn] =
    freeCata(fm)(
      interpret[MapFunc[T, ?], A, OutputM[PartialJs]](
        recovery ⋙ (r => (({ case Nil => r }, List[InputFinder]()): PartialJs).right[PlannerError]),
        javascript)) >>= (_._1.lift(Nil) \/> InternalError("failed JS"))

  def javascript[T[_[_]]: Recursive: ShowT]:
      Algebra[MapFunc[T, ?], OutputM[PartialJs]] = {
    type Output = OutputM[PartialJs]

    import jscore.{
      Add => _, In => _,
      Lt => _, Lte => _, Gt => _, Gte => _, Eq => _, Neq => _,
      And => _, Or => _, Not => _,
      _}

    val HasJs: Output => OutputM[PartialJs] =
      _ <+> \/-(({ case List(field) => field }, List(Here)))

    val HasStr: Output => OutputM[String] = _.flatMap {
      _._1(Nil)(ident("_")) match {
        case Literal(Js.Str(str)) => str.right
        case x => FuncApply("", "JS string", x.toString).left
      }
    }

    def Arity1(a1: Output)(f: JsCore => JsCore): Output =
      HasJs(a1).map {
        case (f1, p1) => ({ case list => JsFn(JsFn.defaultName, f(f1(list)(Ident(JsFn.defaultName)))) }, p1.map(There(0, _)))
      }

    def Arity2(a1: Output, a2: Output)(f: (JsCore, JsCore) => JsCore): Output =
      (HasJs(a1) ⊛ HasJs(a2)) {
        case ((f1, p1), (f2, p2)) =>
          ({ case list => JsFn(JsFn.defaultName, f(f1(list.take(p1.size))(Ident(JsFn.defaultName)), f2(list.drop(p1.size))(Ident(JsFn.defaultName)))) },
            p1.map(There(0, _)) ++ p2.map(There(1, _)))
      }

    def Arity3(a1: Output, a2: Output, a3: Output)(f: (JsCore, JsCore, JsCore) => JsCore): Output =
      (HasJs(a1) ⊛ HasJs(a2) ⊛ HasJs(a3)) {
        case ((f1, p1), (f2, p2), (f3, p3)) =>
          ({ case list => JsFn(JsFn.defaultName, f(
            f1(list.take(p1.size))(Ident(JsFn.defaultName)),
            f2(list.drop(p1.size).take(p2.size))(Ident(JsFn.defaultName)),
            f3(list.drop(p1.size + p2.size))(Ident(JsFn.defaultName))))
          },
            p1.map(There(0, _)) ++ p2.map(There(1, _)) ++ p3.map(There(2, _)))
      }

    def makeSimpleCall(func: String, args: List[JsCore]): JsCore =
      Call(ident(func), args)

    def makeSimpleBinop(op: BinaryOperator, a1: Output, a2: Output): Output =
      Arity2(a1, a2)(BinOp(op, _, _))

    def makeSimpleUnop(op: UnaryOperator, a1: Output): Output =
      Arity1(a1)(UnOp(op, _))

    import MapFuncs._

    {
      case Nullary(v1) => v1.cata(Data.fromEJson).toJs.map[PartialJs](js => ({ case Nil => JsFn.const(js) }, Nil)) \/> NonRepresentableEJson(v1.shows)

      case Length(a1) =>
        Arity1(a1)(expr => Call(ident("NumberLong"), List(Select(expr, "length"))))

      case Date(a1) => Arity1(a1)(str =>
        If(Call(Select(Call(ident("RegExp"), List(Literal(Js.Str("^" + dateRegex + "$")))), "test"), List(str)),
          Call(ident("ISODate"), List(str)),
          ident("undefined")))
      case Time(a1) => Arity1(a1)(str =>
        If(Call(Select(Call(ident("RegExp"), List(Literal(Js.Str("^" + timeRegex + "$")))), "test"), List(str)),
          str,
          ident("undefined")))
      case Timestamp(a1) => Arity1(a1)(str =>
        If(Call(Select(Call(ident("RegExp"), List(Literal(Js.Str("^" + timestampRegex + "$")))), "test"), List(str)),
          Call(ident("ISODate"), List(str)),
          ident("undefined")))
      case Interval(a1) => ???
      case TimeOfDay(a1) => {
        def pad2(x: JsCore) =
          Let(Name("x"), x,
            If(
              BinOp(jscore.Lt, ident("x"), Literal(Js.Num(10, false))),
              BinOp(jscore.Add, Literal(Js.Str("0")), ident("x")),
              ident("x")))
        def pad3(x: JsCore) =
          Let(Name("x"), x,
            If(
              BinOp(jscore.Lt, ident("x"), Literal(Js.Num(100, false))),
              BinOp(jscore.Add, Literal(Js.Str("00")), ident("x")),
              If(
                BinOp(jscore.Lt, ident("x"), Literal(Js.Num(10, false))),
                BinOp(jscore.Add, Literal(Js.Str("0")), ident("x")),
                ident("x"))))
        Arity1(a1)(date =>
          Let(Name("t"), date,
            binop(jscore.Add,
              pad2(Call(Select(ident("t"), "getUTCHours"), Nil)),
              Literal(Js.Str(":")),
              pad2(Call(Select(ident("t"), "getUTCMinutes"), Nil)),
              Literal(Js.Str(":")),
              pad2(Call(Select(ident("t"), "getUTCSeconds"), Nil)),
              Literal(Js.Str(".")),
              pad3(Call(Select(ident("t"), "getUTCMilliseconds"), Nil)))))
      }
      case ToTimestamp(a1) => ???
      case Extract(a1, a2) =>
        // FIXME: Handle non-constant strings as well
        (HasStr(a1) ⊛ HasJs(a2)) {
          case (field, (sel, inputs)) => ((field match {
            case "century"      => \/-(x => BinOp(Div, Call(Select(x, "getFullYear"), Nil), Literal(Js.Num(100, false))))
            case "day"          => \/-(x => Call(Select(x, "getDate"), Nil)) // (day of month)
            case "decade"       => \/-(x => BinOp(Div, Call(Select(x, "getFullYear"), Nil), Literal(Js.Num(10, false))))
            // Note: MongoDB's Date's getDay (during filtering at least) seems to be monday=0 ... sunday=6,
            // apparently in violation of the JavaScript convention.
            case "dow"          =>
              \/-(x => If(BinOp(jscore.Eq,
                Call(Select(x, "getDay"), Nil),
                Literal(Js.Num(6, false))),
                Literal(Js.Num(0, false)),
                BinOp(jscore.Add,
                  Call(Select(x, "getDay"), Nil),
                  Literal(Js.Num(1, false)))))
            // TODO: case "doy"          => \/- (???)
            // TODO: epoch
            case "hour"         => \/-(x => Call(Select(x, "getHours"), Nil))
            case "isodow"       =>
              \/-(x => BinOp(jscore.Add,
                Call(Select(x, "getDay"), Nil),
                Literal(Js.Num(1, false))))
                // TODO: isoyear
            case "microseconds" =>
              \/-(x => BinOp(Mult,
                BinOp(jscore.Add,
                  Call(Select(x, "getMilliseconds"), Nil),
                  BinOp(Mult, Call(Select(x, "getSeconds"), Nil), Literal(Js.Num(1000, false)))),
                Literal(Js.Num(1000, false))))
            case "millennium"   => \/-(x => BinOp(Div, Call(Select(x, "getFullYear"), Nil), Literal(Js.Num(1000, false))))
            case "milliseconds" =>
              \/-(x => BinOp(jscore.Add,
                Call(Select(x, "getMilliseconds"), Nil),
                BinOp(Mult, Call(Select(x, "getSeconds"), Nil), Literal(Js.Num(1000, false)))))
            case "minute"       => \/-(x => Call(Select(x, "getMinutes"), Nil))
            case "month"        =>
              \/-(x => BinOp(jscore.Add,
                Call(Select(x, "getMonth"), Nil),
                Literal(Js.Num(1, false))))
            case "quarter"      =>
              \/-(x => BinOp(jscore.Add,
                BinOp(BitOr,
                  BinOp(Div,
                    Call(Select(x, "getMonth"), Nil),
                    Literal(Js.Num(3, false))),
                  Literal(Js.Num(0, false))),
                Literal(Js.Num(1, false))))
            case "second"       => \/-(x => Call(Select(x, "getSeconds"), Nil))
                // TODO: timezone, timezone_hour, timezone_minute
                // case "week"         => \/- (???)
            case "year"         => \/-(x => Call(Select(x, "getFullYear"), Nil))

            case _ => -\/(FuncApply("extract", "valid time period", field))
          }): PlannerError \/ (JsCore => JsCore)).map(x =>
            ({ case (list: List[JsFn]) => JsFn(JsFn.defaultName, x(sel(list)(Ident(JsFn.defaultName)))) },
              inputs.map(There(1, _))): PartialJs)
        }.join

      case Negate(a1)       => makeSimpleUnop(Neg, a1)
      case Add(a1, a2)      => makeSimpleBinop(jscore.Add, a1, a2)
      case Multiply(a1, a2) => makeSimpleBinop(Mult, a1, a2)
      case Subtract(a1, a2) => makeSimpleBinop(Sub, a1, a2)
      case Divide(a1, a2)   => makeSimpleBinop(Div, a1, a2)
      case Modulo(a1, a2)   => makeSimpleBinop(Mod, a1, a2)
      case Power(a1, a2)    => Arity2(a1, a2)((b, e) =>
        Call(Select(ident("Math"), "pow"), List(b, e)))

      case Not(a1)     => makeSimpleUnop(jscore.Not, a1)
      case Eq(a1, a2)  => makeSimpleBinop(jscore.Eq, a1, a2)
      case Neq(a1, a2) => makeSimpleBinop(jscore.Neq, a1, a2)
      case Lt(a1, a2)  => makeSimpleBinop(jscore.Lt, a1, a2)
      case Lte(a1, a2) => makeSimpleBinop(jscore.Lte, a1, a2)
      case Gt(a1, a2)  => makeSimpleBinop(jscore.Gt, a1, a2)
      case Gte(a1, a2) => makeSimpleBinop(jscore.Gte, a1, a2)
      case IfUndefined(a1, a2) => Arity2(a1, a2)((value, fallback) =>
        // TODO: Only evaluate `value` once.
        If(BinOp(jscore.Eq, value, ident("undefined")), fallback, value))
      case And(a1, a2) => makeSimpleBinop(jscore.And, a1, a2)
      case Or(a1, a2)  => makeSimpleBinop(jscore.Or, a1, a2)
      case Coalesce(a1, a2) => ???
      case Between(a1, a2, a3) => Arity3(a1, a2, a3)((value, min, max) =>
        makeSimpleCall(
          "&&",
          List(
            makeSimpleCall("<=", List(min, value)),
            makeSimpleCall("<=", List(value, max)))))
      case Cond(a1, a2, a3) => Arity3(a1, a2, a3)(If(_, _, _))

      case Within(a1, a2) => Arity2(a1, a2)((value, array) =>
        BinOp(jscore.Neq,
          Literal(Js.Num(-1, false)),
          Call(Select(array, "indexOf"), List(value))))

      case Lower(a1) => Arity1(a1)(str => Call(Select(str, "toLowerCase"), Nil))
      case Upper(a1) => Arity1(a1)(str => Call(Select(str, "toLUpperCase"), Nil))
      case Bool(a1) => Arity1(a1)(str =>
        If(BinOp(jscore.Eq, str, Literal(Js.Str("true"))),
          Literal(Js.Bool(true)),
          If(BinOp(jscore.Eq, str, Literal(Js.Str("false"))),
            Literal(Js.Bool(false)),
            ident("undefined"))))
      case Integer(a1) => Arity1(a1)(str =>
        If(Call(Select(Call(ident("RegExp"), List(Literal(Js.Str("^" + intRegex + "$")))), "test"), List(str)),
          Call(ident("NumberLong"), List(str)),
          ident("undefined")))
      case Decimal(a1) =>
        Arity1(a1)(str =>
          If(Call(Select(Call(ident("RegExp"), List(Literal(Js.Str("^" + floatRegex + "$")))), "test"), List(str)),
            Call(ident("parseFloat"), List(str)),
            ident("undefined")))
      case Null(a1) => Arity1(a1)(str =>
        If(BinOp(jscore.Eq, str, Literal(Js.Str("null"))),
          Literal(Js.Null),
          ident("undefined")))
      case ToString(a1) => Arity1(a1)(value =>
        If(isInt(value),
          // NB: This is a terrible way to turn an int into a string, but the
          //     only one that doesn’t involve converting to a decimal and
          //     losing precision.
          Call(Select(Call(ident("String"), List(value)), "replace"), List(
            Call(ident("RegExp"), List(
              Literal(Js.Str("[^-0-9]+")),
              Literal(Js.Str("g")))),
            Literal(Js.Str("")))),
          If(binop(jscore.Or, isTimestamp(value), isDate(value)),
            Call(Select(value, "toISOString"), Nil),
            Call(ident("String"), List(value)))))
      case Search(a1, a2, a3) => Arity3(a1, a2, a3)((field, pattern, insen) =>
        Call(
          Select(
            New(Name("RegExp"), List(
              pattern,
              If(insen, Literal(Js.Str("im")), Literal(Js.Str("m"))))),
            "test"),
          List(field)))
      case Substring(a1, a2, a3) => Arity3(a1, a2, a3)((field, start, len) =>
        Call(Select(field, "substr"), List(start, len)))
      // case ToId(a1) => Arity1(a1)(id => Call(ident("ObjectId"), List(id)))

      case MakeArray(a1) => ???
      case MakeMap(a1, a2) => ???
      case ConcatArrays(a1, a2) => makeSimpleBinop(jscore.Add, a1, a2)
      case ConcatMaps(a1, a2) => ???
      case ProjectField(a1, a2) => Arity2(a1, a2)(Access(_, _))
      case ProjectIndex(a1, a2)  => Arity2(a1, a2)(Access(_, _))
      case DeleteField(a1, a2)  => ???

      case Guard(expr, typ, cont, fallback) =>
        val jsCheck: Type => Option[JsCore => JsCore] =
          generateTypeCheck[JsCore, JsCore](BinOp(jscore.Or, _, _)) {
            case Type.Null             => isNull
            case Type.Dec              => isDec
            case Type.Int
               | Type.Int ⨿ Type.Dec
               | Type.Int ⨿ Type.Dec ⨿ Type.Interval
                => isAnyNumber
            case Type.Str              => isString
            case Type.Obj(_, _) ⨿ Type.FlexArr(_, _, _)
                => isObjectOrArray
            case Type.Obj(_, _)        => isObject
            case Type.FlexArr(_, _, _) => isArray
            case Type.Binary           => isBinary
            case Type.Id               => isObjectId
            case Type.Bool             => isBoolean
            case Type.Date             => isDate
          }
        jsCheck(typ).fold[OutputM[PartialJs]](
          -\/(InternalError("uncheckable type")))(
          f =>
          (HasJs(expr) ⊛ HasJs(cont) ⊛ HasJs(fallback)) {
            case ((f1, p1), (f2, p2), (f3, p3)) =>
              ({ case list => JsFn(JsFn.defaultName,
                If(f(f1(list.take(p1.size))(Ident(JsFn.defaultName))),
                  f2(list.drop(p1.size).take(p2.size))(Ident(JsFn.defaultName)),
                  f3(list.drop(p1.size + p2.size))(Ident(JsFn.defaultName))))
              },
                p1.map(There(0, _)) ++ p2.map(There(1, _)) ++ p3.map(There(2, _)))
          })

      case DupArrayIndices(_) => ???
      case DupMapKeys(_)      => ???
      case Range(_, _)        => ???
      case ZipArrayIndices(_) => ???
      case ZipMapKeys(_)      => ???
    }
  }

  trait Planner[F[_]] {
    type IT[G[_]]

    /** This is a `GAlgebraM`, but it’s defined as a method so that injection
      * works.
      */
    def plan[WF[_]: Functor: Coalesce: Crush: Crystallize](
      qs: F[OutputM[WorkflowBuilder[WF]]])(
      implicit I: WorkflowOpCoreF :<: WF,
               ev: Show[WorkflowBuilder[WF]],
               WB: WorkflowBuilder.Ops[WF]):
        State[NameGen, OutputM[WorkflowBuilder[WF]]]
  }
  object Planner {
    type Aux[T[_[_]], F[_]] = Planner[F] { type IT[G[_]] = T[G] }

  // NB: Shouldn’t need this once we convert to paths.
  implicit def deadEnd[T[_[_]]]: Planner.Aux[T, Const[DeadEnd, ?]] =
    new Planner[Const[DeadEnd, ?]] {
      type IT[G[_]] = T[G]
      def plan[WF[_]: Functor: Coalesce: Crush: Crystallize](
        qs: Const[DeadEnd, OutputM[WorkflowBuilder[WF]]])(
        implicit I: WorkflowOpCoreF :<: WF,
                 ev: Show[WorkflowBuilder[WF]],
                 WB: WorkflowBuilder.Ops[WF]) =
        // NB: This is just a dummy value. Should never be referenced.
        ValueBuilder(Bson.Null).right.point[State[NameGen, ?]]
    }

  implicit def read[T[_[_]]]: Planner.Aux[T, Const[Read, ?]] =
    new Planner[Const[Read, ?]] {
      type IT[G[_]] = T[G]
      def plan[WF[_]: Functor: Coalesce: Crush: Crystallize](
        qs: Const[Read, OutputM[WorkflowBuilder[WF]]])(
        implicit I: WorkflowOpCoreF :<: WF,
                 ev: Show[WorkflowBuilder[WF]],
                 WB: WorkflowBuilder.Ops[WF]) =
        Collection.fromFile(qs.getConst.path).bimap(PlanPathError(_): PlannerError, WB.read).point[State[NameGen, ?]]
  }


  implicit def sourcedPathable[T[_[_]]: Recursive: ShowT]:
      Planner.Aux[T, SourcedPathable[T, ?]] =
    new Planner[SourcedPathable[T, ?]] {
      type IT[G[_]] = T[G]
      def plan[WF[_]: Functor: Coalesce: Crush: Crystallize](
        qs: SourcedPathable[T, OutputM[WorkflowBuilder[WF]]])(
        implicit I: WorkflowOpCoreF :<: WF,
                 ev: Show[WorkflowBuilder[WF]],
                 WB: WorkflowBuilder.Ops[WF]) =
        qs match {
          case LeftShift(src, struct, repair) => ???
            // (src ⊛ getJsFn(struct) ⊛ getJsMerge(repair))(
            //   (wb, js, jm) =>
            //   WB.jsExpr(
            //     List(wb, WB.flattenMap(WB.jsExpr1(wb, js))),
            //     jm))
          case Union(src, lBranch, rBranch) => ???
        }
    }

  implicit def qscriptCore[T[_[_]]: Recursive: ShowT]:
      Planner.Aux[T, QScriptCore[T, ?]] =
    new Planner[QScriptCore[T, ?]] {
      type IT[G[_]] = T[G]
      def plan[WF[_]: Functor: Coalesce: Crush: Crystallize](
        qs: QScriptCore[T, OutputM[WorkflowBuilder[WF]]])(
        implicit I: WorkflowOpCoreF :<: WF,
                 ev: Show[WorkflowBuilder[WF]],
                 WB: WorkflowBuilder.Ops[WF]) =
        qs match {
          case qscript.Map(src, f) =>
            (src ⊛ getJsFn(f))(WB.jsExpr1).point[State[NameGen, ?]]
          case Reduce(src, bucket, reducers, repair) => ???
          case Sort(src, bucket, order) =>
            val (keys, dirs) = ((bucket, SortDir.Ascending) :: order).unzip
            (src ⊛ keys.traverse(getJsFn[T]))((wb, ks) =>
              WB.sortBy(wb, ks.map(WB.jsExpr1(wb, _)), dirs)).point[State[NameGen, ?]]
          case Filter(src, f) =>
            (src ⊛ getJsFn(f))((wb, js) =>
              WB.filter(wb, List(WB.jsExpr1(wb, js)), {
                case f :: Nil => Selector.Doc(f -> Selector.Eq(Bson.Bool(true)))
              })).point[State[NameGen, ?]]
          case Take(src, from, count) => (for {
            wb <- EitherT(src.point[State[NameGen, ?]])
            c  <- EitherT(rebaseWB(count, wb))
            f  <- EitherT(rebaseWB(from, wb))
            i  <- EitherT(HasInt(c).point[State[NameGen, ?]])
          } yield WB.limit(f, i)).run
          case Drop(src, from, count) => (for {
            wb <- EitherT(src.point[State[NameGen, ?]])
            c  <- EitherT(rebaseWB(count, wb))
            f  <- EitherT(rebaseWB(from, wb))
            i  <- EitherT(HasInt(c).point[State[NameGen, ?]])
          } yield WB.skip(f, i)).run
        }
    }

  implicit def equiJoin[T[_[_]]: Recursive: ShowT]:
      Planner.Aux[T, EquiJoin[T, ?]] =
    new Planner[EquiJoin[T, ?]] {
      type IT[G[_]] = T[G]
      def plan[WF[_]: Functor: Coalesce: Crush: Crystallize](
        qs: EquiJoin[T, OutputM[WorkflowBuilder[WF]]])(
        implicit I: WorkflowOpCoreF :<: WF,
                 ev: Show[WorkflowBuilder[WF]],
                 WB: WorkflowBuilder.Ops[WF]) = ??? // (for {
      //   // FIXME: we should take advantage of the already merged srcs
      //   wb <- EitherT(qs.src.point[State[NameGen, ?]])
      //   lb <- EitherT(rebaseWB(qs.lBranch, wb))
      //   rb <- EitherT(rebaseWB(qs.rBranch, wb))
      //   lk <- EitherT(getJsFn(qs.lKey).point[State[NameGen, ?]])
      //   rk <- EitherT(getJsFn(qs.rKey).point[State[NameGen, ?]])
      // } yield join(lb, rb, qs.f, lk, rk)).run
    }

  // TODO: Remove this instance
  implicit def thetaJoin[T[_[_]]]: Planner.Aux[T, ThetaJoin[T, ?]] =
    new Planner[ThetaJoin[T, ?]] {
      type IT[G[_]] = T[G]
      def plan[WF[_]: Functor: Coalesce: Crush: Crystallize](
        qs: ThetaJoin[T, OutputM[WorkflowBuilder[WF]]])(
        implicit I: WorkflowOpCoreF :<: WF,
                 ev: Show[WorkflowBuilder[WF]],
                 WB: WorkflowBuilder.Ops[WF]) =
        ???
    }

  // TODO: Remove this instance
  implicit def projectBucket[T[_[_]]]: Planner.Aux[T, ProjectBucket[T, ?]] =
    new Planner[ProjectBucket[T, ?]] {
      type IT[G[_]] = T[G]
      def plan[WF[_]: Functor: Coalesce: Crush: Crystallize](
        qs: ProjectBucket[T, OutputM[WorkflowBuilder[WF]]])(
        implicit I: WorkflowOpCoreF :<: WF,
                 ev: Show[WorkflowBuilder[WF]],
                 WB: WorkflowBuilder.Ops[WF]) =
        ???
    }

  implicit def coproduct[T[_[_]], F[_], G[_]](
    implicit F: Planner.Aux[T, F], G: Planner.Aux[T, G]):
      Planner.Aux[T, Coproduct[F, G, ?]] =
    new Planner [Coproduct[F, G, ?]] {
      type IT[G[_]] = T[G]
      def plan[WF[_]: Functor: Coalesce: Crush: Crystallize](
        qs: Coproduct[F, G, OutputM[WorkflowBuilder[WF]]])(
        implicit I: WorkflowOpCoreF :<: WF,
                 ev: Show[WorkflowBuilder[WF]],
                 WB: WorkflowBuilder.Ops[WF]) =
        qs.run.fold(F.plan[WF], G.plan[WF])
    }
  }

  def getJsFn[T[_[_]]: Recursive: ShowT](fm: FreeMap[T]): OutputM[JsFn] =
    processMapFunc(fm)(κ(JsFn.identity))

  def getJsMerge[T[_[_]]: Recursive: ShowT](jf: JoinFunc[T]):
      List[JsCore] => OutputM[JsFn] =
    l => processMapFunc(jf) {
      case LeftSide => JsFn(JsFn.defaultName, l(0))
      case RightSide => JsFn(JsFn.defaultName, l(1))
    }

  def rebaseWB[T[_[_]], WF[_]: Functor: Coalesce: Crush: Crystallize](
    free: FreeQS[T], src: WorkflowBuilder[WF])(
    implicit F: Planner.Aux[T, QScriptTotal[T, ?]],
             I: WorkflowOpCoreF :<: WF,
             ev: Show[WorkflowBuilder[WF]],
             WB: WorkflowBuilder.Ops[WF]):
      State[NameGen, OutputM[WorkflowBuilder[WF]]] =
    freeCataM(free)(
      interpretM[State[NameGen, ?], QScriptTotal[T, ?], qscript.Hole, OutputM[WorkflowBuilder[WF]]](κ(src.right.point[State[NameGen, ?]]), F.plan[WF]))

  def HasLiteral[WF[_]]: WorkflowBuilder[WF] => OutputM[Bson] =
    wb => asLiteral(wb) \/> FuncApply("", "literal", wb.toString)

  def HasInt[WF[_]]: WorkflowBuilder[WF] => OutputM[Long] = HasLiteral(_) >>= {
    case Bson.Int32(v) => \/-(v.toLong)
    case Bson.Int64(v) => \/-(v)
    case x => -\/(FuncApply("", "64-bit integer", x.toString))
  }

  // This is maybe worth putting in Matryoshka?
  def findFirst[T[_[_]]: Recursive, F[_]: Functor: Foldable, A](
    f: PartialFunction[T[F], A]):
      CoalgebraM[A \/ ?, F, T[F]] =
    tf => (f.lift(tf) \/> tf.project).swap

  object Roll {
    def unapply[S[_]: Functor, A](obj: Free[S, A]): Option[S[Free[S, A]]] =
      obj.resume.swap.toOption
  }

  object Point {
    def unapply[S[_]: Functor, A](obj: Free[S, A]): Option[A] = obj.resume.toOption
  }

  def elideMoreGeneralGuards[T[_[_]]](subType: Type):
      FreeMap[T] => PlannerError \/ FreeMap[T] = {
    case free @ Roll(MapFuncs.Guard(Point(SrcHole), typ, cont, _)) =>
      if (typ.contains(subType)) cont.right
      else if (!subType.contains(typ))
        InternalError("can only contain " + subType + ", but a(n) " + typ + " is expected").left
      else free.right
    case x => x.right
  }

  // TODO: Allow backends to provide a “Read” type to the typechecker, which
  //       represents the type of values that can be stored in a collection.
  //       E.g., for MongoDB, it would be `Map(String, Top)`. This will help us
  //       generate more correct PatternGuards in the first place, rather than
  //       trying to strip out unnecessary ones after the fact
  def assumeReadType[T[_[_]]: Recursive, F[_]: Functor](typ: Type)(
    implicit QC: QScriptCore[T, ?] :<: F, R: Const[Read, ?] :<: F):
      QScriptCore[T, T[F]] => PlannerError \/ F[T[F]] = {
    case m @ qscript.Map(src, mf) =>
      R.prj(src.project).fold(
        QC.inj(m).right[PlannerError])(
        κ(mf.transCataTM(elideMoreGeneralGuards(typ)) ∘
          (mf => QC.inj(qscript.Map(src, mf)))))
    case qc => QC.inj(qc).right
  }

  def plan0[T[_[_]]: Recursive: Corecursive: EqualT: ShowT, WF[_]: Functor: Coalesce: Crush: Crystallize](lp: T[LogicalPlan])(
        implicit I: WorkflowOpCoreF :<: WF,
                 ev: Show[WorkflowBuilder[WF]],
                 WB: WorkflowBuilder.Ops[WF]):
      EitherT[Writer[PhaseResults, ?], PlannerError, Crystallized[WF]] = {
    val optimize = new Optimize[T]

    // TODO[scalaz]: Shadow the scalaz.Monad.monadMTMAB SI-2712 workaround
    import EitherT.eitherTMonad

    // NB: Locally add state on top of the result monad so everything
    //     can be done in a single for comprehension.
    type PlanT[X[_], A] = EitherT[X, PlannerError, A]
    type GenT[X[_], A]  = StateT[X, NameGen, A]
    type W[A]           = Writer[PhaseResults, A]
    type F[A]           = PlanT[W, A]
    type M[A]           = GenT[F, A]

    def log[A: RenderTree](label: String)(ma: M[A]): M[A] =
      ma flatMap { a =>
        val result = PhaseResult.Tree(label, RenderTree[A].render(a))
        (Writer(Vector(result), a): W[A]).liftM[PlanT].liftM[GenT]
      }

    def swizzle[A](sa: StateT[PlannerError \/ ?, NameGen, A]): M[A] =
      StateT[F, NameGen, A](ng => EitherT(sa.run(ng).point[W]))

    def liftError[A](ea: PlannerError \/ A): M[A] =
      EitherT(ea.point[W]).liftM[GenT]

    val P = scala.Predef.implicitly[Planner.Aux[T, QScriptTotal[T, ?]]]

    (for {
      // TODO: also need to prefer projections over deletions
      // NB: right now this only outputs one phase, but it’d be cool if we could
      //     interleave phase building in the composed recursion scheme
      qs  <- liftError[T[QScriptTotal[T, ?]]](QueryFile.convertToQScript(lp))
      // opt <- log("QScript (Mongo-specific)")(liftError(
      //   qs.transCataM[PlannerError \/ ?, QScriptTotal[T, ?]](
      //     (optimize.assumeReadType(Type.Obj(ListMap(), Some(Type.Top))) ⋘ optimize.simplifyJoins) ∘
      //       repeatedly(optimize.normalize))))
      // wb  <- log("Workflow Builder")(swizzle(swapM(qs.gcataM[Cofree[QScriptTotal[T, ?], ?], State[NameGen, ?], OutputM[WorkflowBuilder[WF]]](distHisto, P.plan  ⋙ (_ ∘ (_ ∘ (_ ∘ normalize)))))))
      // wf1  <- log("Workflow (raw)")         (swizzle(WorkflowBuilder.build(wb)))
      // wf2  <- log("Workflow (crystallized)")(Crystallize[WF].crystallize(wf1).point[M])
      wb  <- swizzle(swapM(qs.cataM[State[NameGen, ?], OutputM[WorkflowBuilder[WF]]](P.plan(_: QScriptTotal[T, OutputM[WorkflowBuilder[WF]]]) // ∘ (_ ∘ (_ ∘ normalize))
      )))
      wf1  <- swizzle(WorkflowBuilder.build(wb))
      wf2  <- Crystallize[WF].crystallize(wf1).point[M]
    } yield wf2).evalZero
  }

  /** Translate the high-level "logical" plan to an executable MongoDB "physical"
    * plan, taking into account the current runtime environment as captured by
    * the given context (which is for the time being just the "query model"
    * associated with the backend version.)
    * Internally, the type of the plan being built constrains which operators
    * can be used, but the resulting plan uses the largest, common type so that
    * callers don't need to worry about it.
    */
  def plan[T[_[_]]: Recursive: Corecursive: EqualT: ShowT](
    logical: T[LogicalPlan], queryContext: MongoQueryModel):
      EitherT[Writer[PhaseResults, ?], PlannerError, Crystallized[WorkflowF]] = {
    import MongoQueryModel._

    queryContext match {
      case `3.2` =>
        plan0[T, Workflow3_2F](logical)
      case _     =>
        plan0[T, Workflow2_6F](logical).map(_.inject[WorkflowF])
    }
  }
}

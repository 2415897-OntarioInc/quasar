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

package quasar.physical.marklogic.fs

import quasar.Predef._
import quasar.{Planner => QPlanner, RenderTreeT}
import quasar.common._
import quasar.contrib.matryoshka._
import quasar.contrib.pathy._
import quasar.contrib.scalaz._
import quasar.effect.{Capture, Kvs, MonoSeq}
import quasar.fp._, eitherT._
import quasar.fp.numeric.Positive
import quasar.fp.ski.κ
import quasar.fs._
import quasar.fs.impl.queryFileFromDataCursor
import quasar.frontend.logicalplan.LogicalPlan
import quasar.physical.marklogic.qscript._
import quasar.physical.marklogic.xcc._
import quasar.physical.marklogic.xml.NCName
import quasar.physical.marklogic.xquery._, expr._
import quasar.physical.marklogic.xquery.syntax._
import quasar.qscript._

import com.marklogic.xcc.types.XSString
import eu.timepit.refined.auto._
import matryoshka._, Recursive.ops._, FunctorT.ops._
import scalaz._, Scalaz._

object queryfile {
  import QueryFile._
  import FileSystemError._, PathError._
  import FunctionDecl.FunctionDecl3

  type PrologL[F[_]]         = MonadListen_[F, Prologs]
  type MLQScript[T[_[_]], A] = QScriptShiftRead[T, A]

  def interpret[F[_]: Monad: Capture: CSourceReader: SessionReader: XccErr: MonoSeq: PrologW: PrologL, FMT](
    resultsChunkSize: Positive
  )(implicit
    K: Kvs[F, QueryFile.ResultHandle, ResultCursor],
    P: Planner[F, FMT, MLQScript[Fix, ?]]
  ): QueryFile ~> F = {
    type QF[A] = FileSystemErrT[PhaseResultT[F, ?], A]

    def liftQF[A](fa: F[A]): QF[A] =
      fa.liftM[PhaseResultT].liftM[FileSystemErrT]

    def exec(lp: Fix[LogicalPlan], out: AFile) = {
      import MainModule._

      def saveResults(mm: MainModule): QF[MainModule] =
        MonadListen_[QF, Prologs].listen(saveTo[QF](out, mm.queryBody)) map {
          case (body, plogs) =>
            (prologs.modify(_ union plogs) >>> queryBody.set(body))(mm)
        }

      val resultFile = for {
        xqy <- lpToXQuery[QF, Fix, FMT](lp)
        mm  <- saveResults(xqy)
        _   <- liftQF(ops.deleteFile[F](out))
        _   <- liftQF(session.executeModule_[F](mm))
      } yield out

      resultFile.run.run
    }

    def eval(lp: Fix[LogicalPlan]) =
      lpToXQuery[QF, Fix, FMT](lp).flatMap(main => liftQF(
        contentsource.resultCursor[F](resultsChunkSize) { s =>
          SessionReader[F].scope(s)(session.evaluateModule_[F](main))
        }
      )).run.run

    def explain(lp: Fix[LogicalPlan]) =
      lpToXQuery[QF, Fix, FMT](lp)
        .map(main => ExecutionPlan(FsType, main.render))
        .run.run

    def listContents(dir: ADir): F[FileSystemError \/ Set[PathSegment]] =
      ops.exists[F](dir).ifM(
        ops.ls[F](dir).map(_.right[FileSystemError]),
        pathErr(pathNotFound(dir)).left[Set[PathSegment]].point[F])

    queryFileFromDataCursor[ResultCursor, F](exec, eval, explain, listContents, ops.exists[F])
  }

  def lpToXQuery[
    F[_]   : Monad: MonadFsErr: PhaseResultTell: PrologL: Capture: SessionReader: XccErr,
    T[_[_]]: Recursive: Corecursive: EqualT: ShowT: RenderTreeT,
    FMT
  ](
    lp: T[LogicalPlan]
  )(implicit
    planner: Planner[F, FMT, MLQScript[T, ?]]
  ): F[MainModule] = {
    type MLQ[A] = MLQScript[T, A]
    type QSR[A] = QScriptRead[T, A]

    val C = Coalesce[T, MLQ, MLQ]
    val N = Normalizable[MLQ]
    val R = new Rewrite[T]

    def logPhase(pr: PhaseResult): F[Unit] =
      MonadTell_[F, PhaseResults].tell(Vector(pr))

    def plan(qs: T[MLQ]): F[MainModule] =
      MonadListen_[F, Prologs].listen(qs.cataM(planner.plan)) map {
        case (xqy, prologs) => MainModule(Version.`1.0-ml`, prologs, xqy)
      }

    for {
      qs      <- convertToQScriptRead[T, F, QSR](ops.ls[F])(lp)
      shifted =  shiftRead[T, QSR, MLQ].apply(qs)
      _       <- logPhase(PhaseResult.tree("QScript (ShiftRead)", shifted))
      optmzed =  shifted
                   .transAna(
                     repeatedly(C.coalesceQC[MLQ](idPrism)) ⋙
                     repeatedly(C.coalesceTJ[MLQ](idPrism.get)) ⋙
                     repeatedly(C.coalesceSR[MLQ](idPrism)) ⋙
                     repeatedly(N.normalizeF(_: MLQ[T[MLQ]])))
                   .transCata(R.optimize(reflNT))
      _       <- logPhase(PhaseResult.tree("QScript (Optimized)", optmzed))
      main    <- plan(optmzed)
      pp      <- prettyPrint[F](main.queryBody)
      xqyLog  =  MainModule.queryBody.modify(pp getOrElse _)(main).render
      _       <- logPhase(PhaseResult.detail("XQuery", xqyLog))
    } yield main
  }

  def prettyPrint[F[_]: Monad: MonadFsErr: Capture: SessionReader](xqy: XQuery): F[Option[XQuery]] =
    session.resultsOf_[EitherT[F, XccError, ?]](xdmp.prettyPrint(XQuery(s"'$xqy'"))).run flatMap {
      case -\/(err @ XccError.XQueryError(_, cause)) =>
        MonadFsErr[F].raiseError(
          FileSystemError.qscriptPlanningFailed(QPlanner.InternalError(
            (err: XccError).shows, Some(cause))))

      // NB: As this is only for pretty printing, if we fail for some other reason
      //     just return an empty result.
      case -\/(_)  => none[XQuery].point[F]
      case \/-(xs) => (xs.headOption collect { case s: XSString => XQuery(s.asString) }).point[F]
    }

  ////

  private val lpadToLength: FunctionDecl3 =
    declareLocal(NCName("lpad-to-length"))(
      $("padchar") as SequenceType("xs:string"),
      $("length")  as SequenceType("xs:integer"),
      $("str")     as SequenceType("xs:string")
    ).as(SequenceType("xs:string")) { (padchar, length, str) =>
      val (slen, padct, prefix) = ($("slen"), $("padct"), $("prefix"))
      let_(
        slen   := fn.stringLength(str),
        padct  := fn.max(mkSeq_("0".xqy, length - (~slen))),
        prefix := fn.stringJoin(for_($("_") in (1.xqy to ~padct)) return_ padchar, "".xs))
      .return_(
        fn.concat(~prefix, str))
    }

  private def saveTo[F[_]: Monad: QNameGenerator: PrologW](dst: AFile, results: XQuery): F[XQuery] = {
    val dstDirUri = pathUri(asDir(dst))

    for {
      ts     <- freshName[F]
      i      <- freshName[F]
      result <- freshName[F]
      fname  <- freshName[F]
      now    <- lib.secondsSinceEpoch[F].apply(fn.currentDateTime)
      dpart  <- lpadToLength[F]("0".xs, 8.xqy, xdmp.integerToHex(~i))
    } yield {
      let_(
        ts     := xdmp.integerToHex(xs.integer(now * 1000.xqy)),
        $("_") := try_(xdmp.directoryCreate(dstDirUri.xs))
                  .catch_($("e"))(κ(emptySeq)))
      .return_ {
        for_(
          result at i in mkSeq_(results))
        .let_(
          fname := fn.concat(dstDirUri.xs, ~ts, dpart))
        .return_(
          xdmp.documentInsert(~fname, ~result))
      }
    }
  }
}

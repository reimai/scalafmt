package org.scalafmt.internal

import org.scalafmt.Error.UnexpectedTree
import org.scalafmt.config.{ImportSelectors, NewlineCurlyLambda}
import org.scalafmt.internal.ExpiresOn.{Left, Right}
import org.scalafmt.internal.Length.{Num, StateColumn}
import org.scalafmt.internal.Policy.NoPolicy
import org.scalafmt.util._

import scala.collection.mutable
import scala.language.implicitConversions
import scala.meta.classifiers.Classifier
import scala.meta.tokens.{Token, Tokens}
import scala.meta.tokens.{Token => T}
import scala.meta.{
  Case,
  Defn,
  Enumerator,
  Import,
  Init,
  Lit,
  Mod,
  Pat,
  Pkg,
  Template,
  Term,
  Tree,
  Type
}

object Constants {
  val ShouldBeNewline = 100000
  val ShouldBeSingleLine = 30
  val BinPackAssignmentPenalty = 10
  val SparkColonNewline = 10
  val BracketPenalty = 20
  val ExceedColumnPenalty = 1000
  // Breaking a line like s"aaaaaaa${111111 + 22222}" should be last resort.
  val BreakSingleLineInterpolatedString = 10 * ExceedColumnPenalty
  // when converting new A with B with C to
  // new A
  //   with B
  //   with C
  val IndentForWithChains = 2
}

/**
  * Assigns splits to format tokens.
  *
  * NOTE(olafurpg). The pattern match in this file has gotten out of hand. It's
  * difficult even for myself to keep track of what's going on in some cases,
  * especially around applications and lambdas. I'm hoping to sunset this file
  * along with BestFirstSearch in favor of https://github.com/scalameta/scalafmt/issues/917
  */
class Router(formatOps: FormatOps) {

  import Constants._
  import LoggerOps._
  import TokenOps._
  import TreeOps._
  import formatOps._

  private def getSplits(formatToken: FormatToken): Seq[Split] = {
    implicit val style = styleMap.at(formatToken)
    val leftOwner = formatToken.meta.leftOwner
    val rightOwner = formatToken.meta.rightOwner
    val newlines = formatToken.newlinesBetween

    formatToken match {
      case FormatToken(_: T.BOF, _, _) =>
        Seq(
          Split(NoSplit, 0)
        )
      case FormatToken(_, _: T.EOF, _) =>
        Seq(
          Split(Newline, 0) // End files with trailing newline
        )
      case FormatToken(start @ T.Interpolation.Start(), _, _) =>
        val isStripMargin = isMarginizedString(start)
        val end = matching(start)
        val policy =
          if (isTripleQuote(start)) NoPolicy
          else penalizeAllNewlines(end, BreakSingleLineInterpolatedString)
        Seq(
          // statecolumn - 1 because of margin characters |
          Split(NoSplit, 0)
            .onlyIf(isStripMargin)
            .withPolicy(policy)
            .withIndent(StateColumn, end, Left)
            .withIndent(-1, end, Left),
          Split(NoSplit, 0).notIf(isStripMargin).withPolicy(policy)
        )
      case FormatToken(
          T.Interpolation.Id(_) | T.Interpolation.Part(_) |
          T.Interpolation.Start() | T.Interpolation.SpliceStart(),
          _,
          _
          ) =>
        Seq(
          Split(NoSplit, 0)
        )
      case FormatToken(
          _,
          T.Interpolation.Part(_) | T.Interpolation.End() |
          T.Interpolation.SpliceEnd(),
          _
          ) =>
        Seq(
          Split(NoSplit, 0)
        )
      case FormatToken(T.LeftBrace(), T.RightBrace(), _) =>
        Seq(
          Split(NoSplit, 0)
        )
      // Import
      case FormatToken(_: T.Dot, _: T.LeftBrace, _)
          if existsParentOfType[Import](rightOwner) =>
        Seq(
          Split(NoSplit, 0)
        )
      // Import left brace
      case FormatToken(open: T.LeftBrace, _, _)
          if existsParentOfType[Import](leftOwner) =>
        val close = matching(open)
        val disallowSingleLineComments =
          style.importSelectors != ImportSelectors.singleLine
        val policy = SingleLineBlock(
          close,
          disallowSingleLineComments = disallowSingleLineComments
        )
        val newlineBeforeClosingCurly = newlinesOnlyBeforeClosePolicy(close)

        val newlinePolicy = style.importSelectors match {
          case ImportSelectors.noBinPack =>
            newlineBeforeClosingCurly.andThen(OneArgOneLineSplit(formatToken))
          case ImportSelectors.binPack =>
            newlineBeforeClosingCurly
          case ImportSelectors.singleLine =>
            SingleLineBlock(close)
        }

        Seq(
          Split(Space(style.spaces.inImportCurlyBraces), 0)
            .withPolicy(policy),
          Split(Newline, 1)
            .onlyIf(style.importSelectors != ImportSelectors.singleLine)
            .withPolicy(newlinePolicy)
            .withIndent(2, close, Right)
        )
      // Interpolated string left brace
      case FormatToken(open @ T.LeftBrace(), _, _)
          if leftOwner.is[SomeInterpolate] =>
        Seq(
          Split(NoSplit, 0)
        )
      case FormatToken(_, close @ T.RightBrace(), _)
          if rightOwner.is[SomeInterpolate] ||
            existsParentOfType[Import](rightOwner) =>
        val isInterpolate = rightOwner.is[Term.Interpolate]
        Seq(
          Split(
            Space(style.spaces.inImportCurlyBraces && !isInterpolate),
            0
          )
        )
      case FormatToken(_: T.Dot, _: T.Underscore, _)
          if existsParentOfType[Import](rightOwner) =>
        Seq(
          Split(NoSplit, 0)
        )

      // { ... } Blocks
      case tok @ FormatToken(open @ T.LeftBrace(), right, between) =>
        val close = matching(open)
        val closeFT = tokens(close)
        val newlineBeforeClosingCurly = newlinesOnlyBeforeClosePolicy(close)
        val selfAnnotation: Option[Tokens] = leftOwner match {
          // Self type: trait foo { self => ... }
          case t: Template => Some(t.self.name.tokens).filter(_.nonEmpty)
          case _ => None
        }
        val isSelfAnnotation =
          style.optIn.selfAnnotationNewline &&
            newlines > 0 &&
            selfAnnotation.nonEmpty
        val nl: Modification =
          if (isSelfAnnotation) newlines2Modification(newlines, isNoIndent(tok))
          else NewlineT(shouldGet2xNewlines(tok))

        val (lambdaExpire, lambdaArrow, lambdaIndent) =
          statementStarts
            .get(hash(right))
            .collect {
              case owner: Term.Function =>
                val arrow = getFuncArrow(lastLambda(owner))
                val expire =
                  arrow.getOrElse(tokens(owner.params.last.tokens.last))
                (expire, arrow.map(_.left), 0)
            }
            .getOrElse {
              selfAnnotation match {
                case Some(anno) =>
                  val arrow = leftOwner.tokens.find(_.is[T.RightArrow])
                  val expire = arrow.getOrElse(anno.last)
                  (tokens(expire), arrow, 2)
                case _ =>
                  (null, None, 0)
              }
            }
        val lambdaPolicy =
          if (lambdaExpire == null) null
          else {
            val arrowOptimal = getOptimalTokenFor(lambdaExpire)
            newlineBeforeClosingCurly
              .andThen(SingleLineBlock(arrowOptimal))
              .andThen(decideNewlinesOnlyAfterToken(arrowOptimal))
          }

        def getSingleLineDecisionPre2019Nov = leftOwner.parent match {
          case Some(_: Term.If | _: Term.Try | _: Term.TryWithHandler) => null
          case _ => Policy.emptyPf
        }
        def getSingleLineDecisionFor2019Nov = {
          type Classifiers = Seq[Classifier[Token, _]]
          def classifiersByParent: Classifiers = leftOwner.parent match {
            case Some(_: Term.If) => Seq(T.KwElse.classifier)
            case Some(_: Term.Try | _: Term.TryWithHandler) =>
              Seq(T.KwCatch.classifier, T.KwFinally.classifier)
            case _ => Seq.empty
          }
          val classifiers: Classifiers = leftOwner match {
            // for catch with case, we should go up only one level
            case _: Term.Try if rightOwner.is[Case] =>
              Seq(T.KwFinally.classifier)
            case _ if !style.activeForEdition_2020_01 => Seq.empty
            case _ => classifiersByParent
          }

          val breakSingleLineAfterClose = classifiers.nonEmpty && {
            val afterClose = closeFT.right
            classifiers.exists(_(afterClose))
          }
          if (!breakSingleLineAfterClose) Policy.emptyPf
          else decideNewlinesOnlyAfterClose(Split(Newline, 0))(close)
        }
        def getSingleLineDecision: Policy.Pf =
          if (newlines > 0) null
          else if (style.activeForEdition_2019_11)
            getSingleLineDecisionFor2019Nov
          else
            getSingleLineDecisionPre2019Nov

        // null if skipping
        val (singleLineCost, singleLineDecision) =
          if (lambdaPolicy == null) (0, getSingleLineDecision)
          else if (style.activeForEdition_2020_01 &&
            !style.newlines.alwaysBeforeCurlyBraceLambdaParams &&
            getSpaceAndNewlineAfterCurlyLambda(newlines)._1)
            (
              leftOwner.parent
                .flatMap { x =>
                  // penalize single line in the middle of infix
                  x.parent.collect {
                    case y: Term.ApplyInfix if y.lhs eq x => 1
                  }
                }
                .getOrElse(0),
              getSingleLineDecisionFor2019Nov
            )
          else (0, null)

        val singleLineSplit =
          if (singleLineDecision == null) Split.ignored
          else {
            val expire =
              if (lambdaPolicy == null) close
              else endOfSingleLineBlock(closeFT)
            val policy =
              SingleLineBlock(expire, penaliseNewlinesInsideTokens = true)
                .andThen(singleLineDecision)
            Split(xmlSpace(leftOwner), singleLineCost, policy = policy)
              .withOptimalToken(expire, killOnFail = true)
          }

        Seq(
          singleLineSplit,
          Split(Space, 0)
            .notIf(
              style.newlines.alwaysBeforeCurlyBraceLambdaParams ||
                isSelfAnnotation || lambdaPolicy == null
            )
            .withOptimalTokenOpt(lambdaArrow)
            .withIndent(lambdaIndent, close, Right)
            .withPolicy(lambdaPolicy),
          Split(nl, 1)
            .withPolicy(newlineBeforeClosingCurly)
            .withIndent(2, close, Right)
        )
      case FormatToken(arrow @ T.RightArrow(), right, _)
          if statementStarts.contains(hash(right)) &&
            leftOwner.isInstanceOf[Term.Function] =>
        val endOfFunction = lastToken(
          leftOwner.asInstanceOf[Term.Function].body
        )
        val canBeSpace =
          statementStarts(hash(right)).isInstanceOf[Term.Function]
        val (afterCurlySpace, afterCurlyNewlines) =
          getSpaceAndNewlineAfterCurlyLambda(newlines)
        val spaceSplit =
          if (canBeSpace) Split(Space, 0)
          else if (afterCurlySpace && style.activeForEdition_2020_01)
            Split(Space, 0)
              .withPolicy(
                SingleLineBlock(
                  getOptimalTokenFor(endOfFunction),
                  penaliseNewlinesInsideTokens = true
                )
              )
          else Split.ignored
        Seq(
          spaceSplit,
          Split(afterCurlyNewlines, 1).withIndent(2, endOfFunction, Left)
        )

      case FormatToken(T.RightArrow(), right, _)
          if leftOwner.is[Term.Function] =>
        val lambda = leftOwner.asInstanceOf[Term.Function]
        val (endOfFunction, expiresOn) = functionExpire(lambda)
        val hasSingleLineComment = isSingleLineComment(right)
        val indent = // don't indent if the body is empty `{ x => }`
          if (isEmptyFunctionBody(leftOwner) && !right.is[T.Comment]) 0
          else 2
        val singleLineSplit =
          Split(Space, 0)
            .notIf(hasSingleLineComment)
            .withPolicy(SingleLineBlock(endOfFunction))
        def newlineSplit =
          Split(Newline, 1 + nestedApplies(leftOwner))
            .withIndent(indent, endOfFunction, expiresOn)
        val multiLineSplits =
          if (hasSingleLineComment)
            Seq(newlineSplit)
          else if (!style.activeForEdition_2020_01) {
            // older: if followed by an open brace, break after it, else now
            val hasBlock = nextNonComment(formatToken).right.is[T.LeftBrace]
            Seq(if (hasBlock) Split(Space, 0) else newlineSplit)
          } else {
            // 2020-01: break after same-line comments, and any open brace
            val nonComment = nextNonCommentSameLine(formatToken)
            val hasBlock = nonComment.right.is[T.LeftBrace] &&
              (matching(nonComment.right) eq endOfFunction)
            if (!hasBlock && (nonComment eq formatToken))
              Seq(newlineSplit)
            else {
              // break after the brace or comment if fits, or now if doesn't
              // if brace, don't add indent, the LeftBrace rule will do that
              val spaceIndent = if (hasBlock) 0 else indent
              Seq(
                Split(Space, 0)
                  .withIndent(spaceIndent, endOfFunction, expiresOn)
                  .withOptimalToken(getOptimalTokenFor(next(nonComment))),
                newlineSplit
              )
            }
          }
        singleLineSplit +: multiLineSplits

      // Case arrow
      case tok @ FormatToken(arrow @ T.RightArrow(), right, between)
          if leftOwner.isInstanceOf[Case] =>
        val caseStat = leftOwner.asInstanceOf[Case]
        right match {
          case _: T.LeftBrace if caseStat.body eq rightOwner =>
            // Redundant {} block around case statements.
            Seq(
              Split(Space, 0)
                .withIndent(-2, rightOwner.tokens.last, Left)
            )
          case _ =>
            Seq(
              // Gets killed by `case` policy.
              Split(Space, 0).onlyIf(newlines == 0),
              Split(NewlineT(noIndent = rhsIsCommentedOut(tok)), 1)
            )
        }
      // New statement
      case tok @ FormatToken(T.Semicolon(), right, between)
          if startsStatement(tok) && newlines == 0 =>
        val expire = statementStarts(hash(right)).tokens.last
        Seq(
          Split(Space, 0)
            .withOptimalToken(expire)
            .withPolicy(SingleLineBlock(expire)),
          // For some reason, this newline cannot cost 1.
          Split(NewlineT(shouldGet2xNewlines(tok)), 0)
        )

      case tok @ FormatToken(left, right, between) if startsStatement(tok) =>
        val newline: Modification = NewlineT(shouldGet2xNewlines(tok))
        val expire = rightOwner.tokens
          .find(_.is[T.Equals])
          .map { equalsToken =>
            val equalsFormatToken = tokens(equalsToken)
            if (equalsFormatToken.right.is[T.LeftBrace]) {
              equalsFormatToken.right
            } else {
              equalsToken
            }
          }
          .getOrElse(rightOwner.tokens.last)

        val annoRight = right.is[T.At]
        val annoLeft = isSingleIdentifierAnnotation(prev(tok))

        if ((annoRight || annoLeft) && style.optIn.annotationNewlines)
          Seq(Split(newlines2Modification(newlines), 0))
        else {
          val spaceCouldBeOk = annoLeft &&
            newlines == 0 && right.is[Keyword]
          Seq(
            // This split needs to have an optimalAt field.
            Split(Space, 0)
              .onlyIf(spaceCouldBeOk)
              .withOptimalToken(expire)
              .withPolicy(SingleLineBlock(expire)),
            // For some reason, this newline cannot cost 1.
            Split(newline, 0)
          )
        }

      case FormatToken(_, T.RightBrace(), _) =>
        Seq(
          Split(xmlSpace(rightOwner), 0),
          Split(NewlineT(isDouble = newlines > 1), 0)
        )
      case FormatToken(left @ T.KwPackage(), _, _) if leftOwner.is[Pkg] =>
        Seq(
          Split(Space, 0)
        )
      // Opening [ with no leading space.
      // Opening ( with no leading space.
      case FormatToken(
          T.KwSuper() | T.KwThis() | T.Ident(_) | T.RightBracket() |
          T.RightBrace() | T.RightParen() | T.Underscore(),
          T.LeftParen() | T.LeftBracket(),
          _
          ) if noSpaceBeforeOpeningParen(rightOwner) && {
            leftOwner.parent.forall {
              // infix applications have no space.
              case _: Type.ApplyInfix | _: Term.ApplyInfix => false
              case parent => true
            }
          } =>
        val modification: Modification = leftOwner match {
          case _: Mod => Space
          // Add a space between constructor annotations and their parameter lists
          // see:
          // https://github.com/scalameta/scalafmt/pull/1516
          // https://github.com/scalameta/scalafmt/issues/1528
          case init: Init if init.parent.forall(_.is[Mod.Annot]) => Space
          case t: Term.Name
              if style.spaces.afterTripleEquals &&
                t.tokens.map(_.syntax) == Seq("===") =>
            Space
          case name: Term.Name
              if style.spaces.afterSymbolicDefs && isSymbolicName(name.value) && name.parent
                .exists(isDefDef) =>
            Space
          case _ => NoSplit
        }
        Seq(
          Split(modification, 0)
        )
      // Defn.{Object, Class, Trait}
      case tok @ FormatToken(T.KwObject() | T.KwClass() | T.KwTrait(), _, _) =>
        val expire = defnTemplate(leftOwner)
          .flatMap(templateCurly)
          .getOrElse(leftOwner.tokens.last)
        val forceNewlineBeforeExtends = Policy(expire) {
          case d @ Decision(t @ FormatToken(_, _: T.KwExtends, _), _)
              if t.meta.rightOwner == leftOwner =>
            d.onlyNewlinesWithoutFallback
        }
        Seq(
          Split(Space, 0)
            .withOptimalToken(expire, killOnFail = true)
            .withPolicy(SingleLineBlock(expire)),
          Split(Space, 1).withPolicy(forceNewlineBeforeExtends)
        )
      // DefDef
      case tok @ FormatToken(T.KwDef(), name @ T.Ident(_), _) =>
        Seq(
          Split(Space, 0)
        )
      case tok @ FormatToken(e @ T.Equals(), right, _)
          if defBody(leftOwner).isDefined =>
        val expire = defBody(leftOwner).get.tokens.last
        val exclude = getExcludeIf(
          expire, {
            case T.RightBrace() => true
            case close @ T.RightParen()
                if opensConfigStyle(tokens(matching(close))) =>
              // Example:
              // def x = foo(
              //     1
              // )
              true
            case T.RightParen() if !style.newlines.alwaysBeforeMultilineDef =>
              true
            case _ => false
          }
        )

        val rhsIsJsNative = isJsNative(right)
        right match {
          case T.LeftBrace() =>
            // The block will take care of indenting by 2.
            Seq(Split(Space, 0))
          case _ =>
            val rhsIsComment = isSingleLineComment(right)
            Seq(
              Split(Space, 0)
                .notIf(rhsIsComment || newlines != 0 && !rhsIsJsNative)
                .withPolicy(
                  if (!style.newlines.alwaysBeforeMultilineDef) NoPolicy
                  else SingleLineBlock(expire, exclude = exclude)
                ),
              Split(Space, 0)
                .onlyIf(newlines == 0 && rhsIsComment)
                .withIndent(2, expire, Left),
              Split(Newline, 1)
                .notIf(rhsIsJsNative)
                .withIndent(2, expire, Left)
            )
        }

      // Parameter opening for one parameter group. This format works
      // on the WHOLE defnSite (via policies)
      case ft @ FormatToken((T.LeftParen() | T.LeftBracket()), _, _)
          if style.verticalMultiline.atDefnSite &&
            isDefnSiteWithParams(leftOwner) =>
        verticalMultiline(leftOwner, ft)(style)

      // Term.Apply and friends
      case FormatToken(T.LeftParen(), _, _)
          if style.optIn.configStyleArguments &&
            !style.newlinesBeforeSingleArgParenLambdaParams &&
            getLambdaAtSingleArgCallSite(formatToken).isDefined => {
        val lambda = getLambdaAtSingleArgCallSite(formatToken).get
        val lambdaLeft: Option[Token] =
          matchingOpt(functionExpire(lambda)._1).filter(_.is[T.LeftBrace])

        val arrowFt = getFuncArrow(lambda).get
        val lambdaIsABlock = lambdaLeft.exists(_ eq arrowFt.right)
        val lambdaToken =
          getOptimalTokenFor(if (lambdaIsABlock) next(arrowFt) else arrowFt)

        val close = matching(formatToken.left)
        val newlinePolicy =
          if (!style.danglingParentheses.callSite) None
          else Some(newlinesOnlyBeforeClosePolicy(close))
        val spacePolicy = SingleLineBlock(lambdaToken).orElse {
          if (lambdaIsABlock) None
          else
            newlinePolicy.map(
              delayedBreakPolicy(lambdaLeft.map(open => _.end < open.end))
            )
        }

        val noSplitMod = getNoSplit(formatToken, true)
        val newlinePenalty = 3 + nestedApplies(leftOwner)
        Seq(
          Split(noSplitMod, 0, policy = SingleLineBlock(close))
            .onlyIf(noSplitMod != null)
            .withOptimalToken(close),
          Split(noSplitMod, 0, policy = spacePolicy)
            .onlyIf(noSplitMod != null)
            .withOptimalToken(lambdaToken),
          Split(Newline, newlinePenalty)
            .withPolicyOpt(newlinePolicy)
            .withIndent(style.continuationIndent.callSite, close, Right)
        )
      }

      case FormatToken(T.LeftParen() | T.LeftBracket(), right, between)
          if style.optIn.configStyleArguments && isDefnOrCallSite(leftOwner) &&
            (opensConfigStyle(formatToken) || {
              forceConfigStyle(leftOwner) && !styleMap.forcedBinPack(leftOwner)
            }) =>
        val open = formatToken.left
        val indent = getApplyIndent(leftOwner, isConfigStyle = true)
        val close = matching(open)
        val newlineBeforeClose = newlinesOnlyBeforeClosePolicy(close)
        val extraIndent: Length =
          if (style.poorMansTrailingCommasInConfigStyle) Num(2)
          else Num(0)
        val isForcedBinPack = styleMap.forcedBinPack.contains(leftOwner)
        val policy =
          if (isForcedBinPack) newlineBeforeClose
          else OneArgOneLineSplit(formatToken).orElse(newlineBeforeClose)
        val implicitSplit =
          if (opensConfigStyleImplicitParamList(formatToken))
            Split(Space(style.spaces.inParentheses), 0)
              .withPolicy(policy.orElse(decideNewlinesOnlyAfterToken(right)))
              .withOptimalToken(right, killOnFail = true)
              .withIndent(indent, close, Right)
              .withIndent(extraIndent, right, Right)
          else Split.ignored
        Seq(
          implicitSplit,
          Split(Newline, if (implicitSplit.isActive) 1 else 0, policy = policy)
            .withIndent(indent, close, Right)
            .withIndent(extraIndent, right, Right)
        )

      case FormatToken(open @ (T.LeftParen() | T.LeftBracket()), right, between)
          if style.binPack.unsafeDefnSite && isDefnSite(leftOwner) =>
        val close = matching(open)
        val isBracket = open.is[T.LeftBracket]
        val indent = Num(style.continuationIndent.defnSite)
        if (isTuple(leftOwner)) {
          Seq(
            Split(NoSplit, 0).withPolicy(
              SingleLineBlock(close, disallowSingleLineComments = false)
            )
          )
        } else {
          def penalizeBrackets(penalty: Int): Policy =
            if (isBracket)
              penalizeAllNewlines(close, Constants.BracketPenalty * penalty + 3)
            else NoPolicy
          val bracketCoef = if (isBracket) Constants.BracketPenalty else 1
          val bracketPenalty = if (isBracket) 1 else 0
          val nestingPenalty = nestedApplies(leftOwner)

          val noSplitPenalizeNewlines = penalizeBrackets(1 + bracketPenalty)
          val noSplitPolicy: Policy = argumentStarts.get(hash(right)) match {
            case Some(arg) =>
              val singleLine = SingleLineBlock(arg.tokens.last)
              if (isBracket) {
                noSplitPenalizeNewlines.andThen(singleLine.f)
              } else {
                singleLine
              }
            case _ => noSplitPenalizeNewlines
          }
          val noSplitModification =
            if (right.is[T.Comment]) newlines2Modification(newlines)
            else NoSplit

          Seq(
            Split(noSplitModification, 0 + (nestingPenalty * bracketCoef))
              .withPolicy(noSplitPolicy)
              .withIndent(indent, close, Left),
            Split(Newline, (1 + nestingPenalty * nestingPenalty) * bracketCoef)
              .notIf(right.is[T.RightParen])
              .withPolicy(penalizeBrackets(1))
              .withIndent(indent, close, Left)
          )
        }
      case FormatToken(T.LeftParen() | T.LeftBracket(), _, _)
          if style.binPack.unsafeCallSite && isCallSite(leftOwner) =>
        val open = formatToken.left
        val close = matching(open)
        val indent = getApplyIndent(leftOwner)
        val (_, args) = getApplyArgs(formatToken, false)
        val optimal = leftOwner.tokens.find(_.is[T.Comma]).orElse(Some(close))
        val isBracket = open.is[T.LeftBracket]
        // TODO(olafur) DRY. Same logic as in default.
        val exclude =
          if (isBracket)
            insideBlock(formatToken, close, _.isInstanceOf[T.LeftBracket])
          else
            insideBlock(formatToken, close, x => x.isInstanceOf[T.LeftBrace])
        val excludeRanges = exclude.map(parensRange)
        val unindent =
          UnindentAtExclude(exclude, Num(-style.continuationIndent.callSite))
        val unindentPolicy =
          if (args.length == 1) Policy(close)(unindent)
          else NoPolicy
        def ignoreBlocks(x: FormatToken): Boolean = {
          excludeRanges.exists(_.contains(x.left.end))
        }
        val noSplitPolicy =
          penalizeAllNewlines(close, 3, ignore = ignoreBlocks)
            .andThen(unindent)
        Seq(
          Split(NoSplit, 0)
            .withOptimalTokenOpt(optimal)
            .withPolicy(noSplitPolicy)
            .withIndent(indent, close, Left),
          Split(Newline, 2)
            .withPolicy(unindentPolicy)
            .withIndent(4, close, Left)
        )
      case FormatToken(T.LeftParen(), T.RightParen(), _) =>
        Seq(Split(NoSplit, 0))

      // If configured to skip the trailing space after `if` and other keywords, do so.
      case FormatToken(T.KwIf() | T.KwFor() | T.KwWhile(), T.LeftParen(), _)
          if !style.spaces.afterKeywordBeforeParen =>
        Seq(Split(NoSplit, 0))

      case tok @ FormatToken(T.LeftParen() | T.LeftBracket(), right, between)
          if !isSuperfluousParenthesis(formatToken.left, leftOwner) &&
            (!style.binPack.unsafeCallSite && isCallSite(leftOwner)) ||
            (!style.binPack.unsafeDefnSite && isDefnSite(leftOwner)) =>
        val open = tok.left
        val close = matching(open)
        val (lhs, args) = getApplyArgs(formatToken, false)
        // In long sequence of select/apply, we penalize splitting on
        // parens furthest to the right.
        val lhsPenalty = treeDepth(lhs)

        // XXX: sometimes we have zero args, so multipleArgs != !singleArgument
        val singleArgument = args.length == 1
        val multipleArgs = args.length > 1
        val notTooManyArgs = multipleArgs && args.length <= 100

        val isBracket = open.is[T.LeftBracket]
        val bracketCoef = if (isBracket) Constants.BracketPenalty else 1

        val nestedPenalty = nestedApplies(leftOwner) + lhsPenalty
        val exclude =
          if (isBracket) insideBlock(tok, close, _.is[T.LeftBracket])
          else if (style.activeForEdition_2020_03 && multipleArgs)
            Set.empty[Token]
          else
            insideBlock(tok, close, x => x.is[T.LeftBrace])
        val excludeRanges = exclude.map(parensRange)

        val indent = getApplyIndent(leftOwner)

        def insideBraces(t: FormatToken): Boolean =
          excludeRanges.exists(_.contains(t.left.start))

        def singleLine(
            newlinePenalty: Int
        )(implicit line: sourcecode.Line): Policy = {
          val baseSingleLinePolicy = if (isBracket) {
            if (singleArgument)
              penalizeAllNewlines(
                close,
                newlinePenalty,
                penalizeLambdas = false
              )
            else SingleLineBlock(close)
          } else {
            val penalty =
              if (singleArgument) newlinePenalty
              else Constants.ShouldBeNewline
            penalizeAllNewlines(
              close,
              penalty = penalty,
              ignore = insideBraces,
              penalizeLambdas = !singleArgument,
              penaliseNewlinesInsideTokens = !singleArgument
            )
          }

          baseSingleLinePolicy
        }

        val newlineMod: Modification = NoSplit.orNL(right.is[T.LeftBrace])

        val defnSite = isDefnSite(leftOwner)
        val closeFormatToken = tokens(close)
        val expirationToken: Token =
          if (defnSite && !isBracket)
            defnSiteLastToken(closeFormatToken, leftOwner)
          else
            rhsOptimalToken(closeFormatToken)

        val mustDangle = style.activeForEdition_2020_01 && (
          expirationToken.is[T.Comment]
        )
        val wouldDangle =
          if (defnSite) style.danglingParentheses.defnSite
          else style.danglingParentheses.callSite

        val newlinePolicy: Policy =
          if (wouldDangle || mustDangle) {
            newlinesOnlyBeforeClosePolicy(close)
          } else {
            Policy.empty(close)
          }

        val handleImplicit = style.activeForEdition_2020_03 &&
          opensImplicitParamList(formatToken, args)

        val noSplitMod =
          if (handleImplicit && style.newlines.beforeImplicitParamListModifier)
            null
          else getNoSplit(formatToken, !isBracket)
        val noSplitIndent = if (right.is[T.Comment]) indent else Num(0)

        val align =
          if (defnSite) style.align.openParenDefnSite
          else style.align.openParenCallSite
        val alignTuple = align && isTuple(leftOwner)

        val keepConfigStyleSplit =
          style.optIn.configStyleArguments && newlines != 0
        val splitsForAssign =
          if (defnSite || isBracket || keepConfigStyleSplit) None
          else
            getAssignAtSingleArgCallSite(leftOwner).map { assign =>
              val assignToken = assign.rhs match {
                case b: Term.Block => b.tokens.head
                case _ => assign.tokens.find(_.is[T.Equals]).get
              }
              val breakToken = getOptimalTokenFor(assignToken)
              val newlineAfterAssignDecision =
                if (newlinePolicy.isEmpty) Policy.emptyPf
                else decideNewlinesOnlyAfterToken(breakToken)
              val noSplitCost = 1 + nestedPenalty
              val newlineCost = Constants.ExceedColumnPenalty + noSplitCost
              Seq(
                Split(Newline, newlineCost)
                  .withPolicy(newlinePolicy)
                  .withIndent(indent, close, Right),
                Split(NoSplit, noSplitCost)
                  .withOptimalToken(breakToken)
                  .withPolicy(
                    newlinePolicy
                      .andThen(newlineAfterAssignDecision)
                      .andThen(SingleLineBlock(breakToken))
                  )
              )
            }

        val noSplitPolicy =
          if (wouldDangle || mustDangle && isBracket)
            SingleLineBlock(close, exclude = excludeRanges)
          else if (splitsForAssign.isDefined)
            singleLine(3)
          else
            singleLine(10)
        val oneArgOneLine =
          newlinePolicy.andThen(OneArgOneLineSplit(formatToken))
        Seq(
          Split(noSplitMod, 0, policy = noSplitPolicy)
            .onlyIf(noSplitMod != null)
            .withOptimalToken(expirationToken)
            .withIndent(noSplitIndent, close, Right),
          Split(newlineMod, (1 + nestedPenalty) * bracketCoef)
            .withPolicy(newlinePolicy.andThen(singleLine(4)))
            .onlyIf(!multipleArgs && !alignTuple && splitsForAssign.isEmpty)
            .withOptimalToken(expirationToken)
            .withIndent(indent, close, Right),
          Split(noSplitMod, (2 + lhsPenalty) * bracketCoef)
            .withPolicy(oneArgOneLine)
            .onlyIf(handleImplicit || (notTooManyArgs && align))
            .onlyIf(noSplitMod != null)
            .withOptimalToken(expirationToken)
            .withIndent(if (align) StateColumn else indent, close, Right),
          Split(Newline, (3 + nestedPenalty) * bracketCoef)
            .withPolicy(oneArgOneLine)
            .onlyIf(!singleArgument && !alignTuple)
            .withIndent(indent, close, Right)
        ) ++ splitsForAssign.getOrElse(Seq.empty)

      // Closing def site ): ReturnType
      case FormatToken(left, T.Colon(), _)
          if style.newlines.sometimesBeforeColonInMethodReturnType &&
            defDefReturnType(leftOwner).isDefined =>
        val expire = lastToken(defDefReturnType(rightOwner).get)
        val penalizeNewlines =
          penalizeAllNewlines(expire, Constants.BracketPenalty)
        val sameLineSplit = Space(endsWithSymbolIdent(left))
        Seq(
          Split(sameLineSplit, 0).withPolicy(penalizeNewlines),
          // Spark style guide allows this:
          // https://github.com/databricks/scala-style-guide#indent
          Split(Newline, Constants.SparkColonNewline)
            .withIndent(style.continuationIndent.defnSite, expire, Left)
            .withPolicy(penalizeNewlines)
        )
      case FormatToken(T.Colon(), _, _)
          if style.newlines.neverInResultType &&
            defDefReturnType(leftOwner).isDefined =>
        val expire = lastToken(defDefReturnType(leftOwner).get)
        Seq(
          Split(Space, 0).withPolicy(
            SingleLineBlock(expire, disallowSingleLineComments = false)
          )
        )

      case FormatToken(T.LeftParen(), T.LeftBrace(), between) =>
        Seq(
          Split(NoSplit, 0)
        )

      case FormatToken(_, T.LeftBrace(), _) if isXmlBrace(rightOwner) =>
        Seq(
          Split(NoSplit, 0)
        )
      case FormatToken(T.RightBrace(), _, _) if isXmlBrace(leftOwner) =>
        Seq(
          Split(NoSplit, 0)
        )
      // non-statement starting curly brace
      case FormatToken(_: T.Comma, open: T.LeftBrace, _)
          if !style.poorMansTrailingCommasInConfigStyle && {
            if (isCallSite(leftOwner)) !style.binPack.unsafeCallSite
            else isDefnSite(leftOwner) && !style.binPack.unsafeDefnSite
          } =>
        val close = matching(open)
        val oneArgPerLineSplits =
          if (!style.activeForEdition_2020_03) Seq.empty
          else
            (rightOwner match {
              case _: Term.PartialFunction | Term.Block(
                    List(_: Term.Function | _: Term.PartialFunction)
                  ) =>
                Seq(Split(Newline, 0))
              case _ =>
                val breakAfter =
                  rhsOptimalToken(next(nextNonCommentSameLine(formatToken)))
                val multiLine =
                  newlinesOnlyBeforeClosePolicy(close)
                    .orElse(decideNewlinesOnlyAfterToken(breakAfter))
                Seq(
                  Split(Newline, 0, policy = SingleLineBlock(close))
                    .withOptimalToken(close, killOnFail = true),
                  Split(Space, 1, policy = multiLine)
                )
            }).map(_.onlyFor(SplitTag.OneArgPerLine))
        def oneLineBody = open.pos.endLine == close.pos.startLine
        Seq(
          Split(Space, 0),
          Split(Newline, 0)
            .onlyIf(newlines != 0 && oneLineBody)
            .withOptimalToken(close, killOnFail = true)
            .withPolicy(SingleLineBlock(close))
        ) ++ oneArgPerLineSplits
      case FormatToken(_, _: T.LeftBrace, _) =>
        Seq(Split(Space, 0))

      // Delim
      case FormatToken(_, T.Comma(), _) =>
        Seq(
          Split(NoSplit, 0)
        )
      // These are mostly filtered out/modified by policies.
      case tok @ FormatToken(T.Comma(), right, _) =>
        // TODO(olafur) DRY, see OneArgOneLine.
        val binPack = isBinPack(leftOwner)
        val isInfix = leftOwner.isInstanceOf[Term.ApplyInfix]
        argumentStarts.get(hash(right)) match {
          case Some(nextArg) if binPack =>
            val lastFT = tokens(nextArg.tokens.last)
            val nextComma: Option[FormatToken] = tokens(lastFT, 1) match {
              case t @ FormatToken(_: T.Comma, _, _)
                  if t.meta.leftOwner == leftOwner =>
                Some(t)
              case _ => None
            }
            val singleLine = SingleLineBlock(lastFT.left)
            val breakOnNextComma = nextComma match {
              case Some(comma) =>
                Policy(comma.right) {
                  case d @ Decision(t, s) if comma == t =>
                    d.forceNewline
                }
              case _ => NoPolicy
            }
            val optToken = nextComma.map(_ =>
              OptimalToken(
                rhsOptimalToken(lastFT),
                killOnFail = true
              )
            )
            Seq(
              Split(Space, 0, optimalAt = optToken).withPolicy(singleLine),
              Split(Newline, 1, optimalAt = optToken).withPolicy(singleLine),
              // next argument doesn't fit on a single line, break on comma before
              // and comma after.
              Split(Newline, 2, optimalAt = optToken)
                .withPolicy(breakOnNextComma)
            )
          case _ if isInfix =>
            Seq(
              // Do whatever the user did if infix.
              Split(Space.orNL(newlines == 0), 0)
            )
          case _ =>
            val indent = leftOwner match {
              case _: Defn.Val | _: Defn.Var =>
                style.continuationIndent.defnSite
              case _ =>
                0
            }
            val singleLineComment = isSingleLineComment(right)
            val noNewline = newlines == 0 && {
              singleLineComment || style.activeForEdition_2020_01 && {
                val nextTok = nextNonComment(tok).right
                // perhaps a trailing comma
                (nextTok ne right) && nextTok.is[RightParenOrBracket]
              }
            }
            Seq(
              Split(Space, 0).notIf(newlines != 0 && singleLineComment),
              Split(Newline, 1)
                .notIf(noNewline)
                .withIndent(indent, right, ExpiresOn.Right)
            )
        }
      case FormatToken(_, T.Semicolon(), _) =>
        Seq(
          Split(NoSplit, 0)
        )
      case FormatToken(T.KwReturn(), _, _) =>
        val mod = leftOwner match {
          case Term.Return(unit @ Lit.Unit()) if unit.tokens.isEmpty =>
            // Always force blank line for Unit "return".
            Newline
          case _ =>
            Space
        }
        Seq(
          Split(mod, 0)
        )
      case FormatToken(left, T.Colon(), _) =>
        val mod: Modification = rightOwner match {
          case tp: Type.Param =>
            val contextOption = style.spaces.beforeContextBoundColon
            val summaryTypeBoundsCount =
              tp.tbounds.lo.size + tp.tbounds.hi.size + tp.cbounds.size
            val useSpace = contextOption.isAlways ||
              contextOption.isIfMultipleBounds && summaryTypeBoundsCount > 1
            Space(useSpace)

          case _ =>
            left match {
              case ident: T.Ident => identModification(ident)
              case _ => NoSplit
            }
        }
        Seq(
          Split(mod, 0)
        )
      // Only allow space after = in val if rhs is a single line or not
      // an infix application or an if. For example, this is allowed:
      // val x = function(a,
      //                  b)
      case FormatToken(tok @ T.Equals(), right, between) if (leftOwner match {
            case _: Defn.Type | _: Defn.Val | _: Defn.Var | _: Term.Assign |
                _: Term.Assign | _: Term.Assign =>
              true
            case t: Term.Param => t.default.isDefined
            case _ => false
          }) =>
        val rhs: Tree = leftOwner match {
          case l: Term.Assign => l.rhs
          case l: Term.Param => l.default.get
          case l: Defn.Type => l.body
          case l: Defn.Val => l.rhs
          case r: Defn.Var =>
            r.rhs match {
              case Some(x) => x
              case None => r // var x: Int = _, no policy
            }
        }

        def wouldDangle = {
          val dangleStyle = style.danglingParentheses
          (dangleStyle.defnSite && leftOwner.parent.exists(isDefnSite)) ||
          (dangleStyle.callSite && leftOwner.parent.exists(isCallSite))
        }

        val expire = rhs.tokens.last
        // rhsOptimalToken is too aggressive here
        val optimal = tokens(expire).right match {
          case x: T.Comma => x
          case x @ RightParenOrBracket() if !wouldDangle => x
          case _ => expire
        }

        val penalty = leftOwner match {
          case l: Term.Assign if style.binPack.unsafeCallSite =>
            Constants.BinPackAssignmentPenalty
          case l: Term.Param if style.binPack.unsafeDefnSite =>
            Constants.BinPackAssignmentPenalty
          case _ => 0
        }

        val exclude =
          insideBlock(formatToken, expire, _.isInstanceOf[T.LeftBrace])
        rhs match {
          case t: Term.ApplyInfix =>
            infixSplit(t, formatToken)
          case _ =>
            def twoBranches: Policy = {
              val excludeRanges = exclude.map(parensRange)
              penalizeAllNewlines(
                expire,
                Constants.ShouldBeSingleLine,
                ignore = x => excludeRanges.exists(_.contains(x.left.start))
              )
            }
            val jsNative = isJsNative(right)
            val noNewline = jsNative
            val spacePolicy: Policy = rhs match {
              case _: Term.If => twoBranches
              case _: Term.ForYield => twoBranches
              case _: Term.Try | _: Term.TryWithHandler
                  if style.activeForEdition_2019_11 && !noNewline =>
                null // we force newlines in try/catch/finally
              case _ => NoPolicy
            }
            val noSpace = null == spacePolicy ||
              (!jsNative && newlines > 0 && leftOwner.isInstanceOf[Defn])
            val spaceIndent = if (isSingleLineComment(right)) 2 else 0
            Seq(
              Split(Space, 0, policy = spacePolicy)
                .notIf(noSpace)
                .withOptimalToken(optimal)
                .withIndent(spaceIndent, expire, Left),
              Split(Newline, 1 + penalty)
                .notIf(noNewline)
                .withIndent(2, expire, Left)
            )
        }

      case FormatToken(T.Ident(name), _: T.Dot, _) if isSymbolicName(name) =>
        Seq(Split(NoSplit, 0))

      case FormatToken(_: T.Underscore, _: T.Dot, _) =>
        Seq(Split(NoSplit, 0))

      case tok @ FormatToken(left, dot @ T.Dot() `:chain:` chain, _) =>
        val nestedPenalty = nestedSelect(rightOwner) + nestedApplies(leftOwner)
        val optimalToken = chainOptimalToken(chain)
        val expire =
          if (chain.length == 1) lastToken(chain.last)
          else optimalToken

        val breakOnEveryDot = Policy(expire) {
          case Decision(t @ FormatToken(_, _: T.Dot, _), _)
              if chain.contains(t.meta.rightOwner) =>
            val mod = NoSplit.orNL(
              style.optIn.breaksInsideChains && t.newlinesBetween == 0
            )
            Seq(Split(mod, 1))
        }
        val exclude = getExcludeIf(expire)
        // This policy will apply to both the space and newline splits, otherwise
        // the newline is too cheap even it doesn't actually prevent other newlines.
        val penalizeNewlinesInApply = penalizeAllNewlines(expire, 2)
        val noSplitPolicy = SingleLineBlock(expire, exclude)
          .andThen(penalizeNewlinesInApply.f)
          .copy(expire = expire.end)
        val newlinePolicy = breakOnEveryDot
          .andThen(penalizeNewlinesInApply.f)
          .copy(expire = expire.end)
        val ignoreNoSplit =
          style.optIn.breakChainOnFirstMethodDot && newlines > 0
        val chainLengthPenalty =
          if (style.newlines.penalizeSingleSelectMultiArgList &&
            chain.length < 2) {
            // penalize by the number of arguments in the rhs open apply.
            // I know, it's a bit arbitrary, but my manual experiments seem
            // to show that it produces OK output. The key insight is that
            // many arguments on the same line can be hard to read. By not
            // putting a newline before the dot, we force the argument list
            // to break into multiple lines.
            splitCallIntoParts.lift(tokens(tok, 2).meta.rightOwner) match {
              case Some((_, util.Left(args))) =>
                Math.max(0, args.length - 1)
              case Some((_, util.Right(argss))) =>
                Math.max(0, argss.map(_.length).sum - 1)
              case _ => 0
            }
          } else 0
        Seq(
          Split(NoSplit, 0)
            .notIf(ignoreNoSplit)
            .withPolicy(noSplitPolicy),
          Split(
            Newline.copy(acceptNoSplit = true),
            2 + nestedPenalty + chainLengthPenalty
          ).withPolicy(newlinePolicy)
            .withIndent(2, optimalToken, Left)
        )

      // ApplyUnary
      case tok @ FormatToken(T.Ident(_), Literal(), _)
          if leftOwner == rightOwner =>
        Seq(
          Split(NoSplit, 0)
        )
      case FormatToken(op @ T.Ident(_), right, _) if leftOwner.parent.exists {
            case unary: Term.ApplyUnary =>
              unary.op.tokens.head == op
            case _ => false
          } =>
        Seq(
          Split(Space(isSymbolicIdent(right)), 0)
        )
      // Annotations, see #183 for discussion on this.
      case FormatToken(_, bind @ T.At(), _) if rightOwner.is[Pat.Bind] =>
        Seq(
          Split(Space, 0)
        )
      case FormatToken(bind @ T.At(), _, _) if leftOwner.is[Pat.Bind] =>
        Seq(
          Split(Space, 0)
        )
      case FormatToken(T.At(), right @ Delim(), _) =>
        Seq(Split(NoSplit, 0))
      case FormatToken(T.At(), right @ T.Ident(_), _) =>
        // Add space if right starts with a symbol
        Seq(Split(identModification(right), 0))

      // Template
      case FormatToken(_, right @ T.KwExtends(), _) =>
        val template = defnTemplate(rightOwner)
        val lastToken = template
          .flatMap(templateCurly)
          .orElse(template.map(_.tokens.last))
          .getOrElse(rightOwner.tokens.last)
        binPackParentConstructorSplits(
          template.toSet,
          lastToken,
          style.continuationIndent.extendSite
        )
      case FormatToken(_, T.KwWith(), _) =>
        rightOwner match {
          // something like new A with B with C
          case template: Template if template.parent.exists { p =>
                p.is[Term.New] || p.is[Term.NewAnonymous]
              } =>
            val isFirstWith = template.inits.headOption.exists { init =>
              // [init.tpe == leftOwner] part is about expressions like [new A with B]
              // [leftOwner.is[Init] && init == leftOwner] part is about expressions like [new A(x) with B]
              leftOwner.is[Init] && init == leftOwner || init.tpe == leftOwner
            }
            splitWithChain(
              isFirstWith,
              Set(template),
              templateCurly(template).getOrElse(template.tokens.last)
            )

          case template: Template =>
            val hasSelfAnnotation = template.self.tokens.nonEmpty
            val expire = templateCurly(rightOwner)
            val policy =
              if (hasSelfAnnotation) NoPolicy
              else
                Policy(expire) {
                  // Force template to be multiline.
                  case d @ Decision(
                        t @ FormatToken(_: T.LeftBrace, right, _),
                        _
                      )
                      if !hasSelfAnnotation &&
                        !right.is[T.RightBrace] && // corner case, body is {}
                        childOf(template, t.meta.leftOwner) =>
                    d.forceNewline
                }
            Seq(
              Split(Space, 0),
              Split(Newline, 1).withPolicy(policy)
            )
          // trait A extends B with C with D with E
          case t @ WithChain(top) =>
            splitWithChain(
              !t.lhs.is[Type.With],
              withChain(top).toSet,
              top.tokens.last
            )

          case _ =>
            Seq(Split(Space, 0))
        }
      // If/For/While/For with (
      case FormatToken(open: T.LeftParen, _, _) if (leftOwner match {
            case _: Term.If | _: Term.While | _: Term.For | _: Term.ForYield =>
              !isSuperfluousParenthesis(open, leftOwner)
            case _ => false
          }) =>
        val close = matching(open)
        val penalizeNewlines = penalizeNewlineByNesting(open, close)
        val indent: Length =
          if (style.align.ifWhileOpenParen) StateColumn
          else style.continuationIndent.callSite
        Seq(
          Split(NoSplit, 0)
            .withIndent(indent, close, Left)
            .withPolicy(penalizeNewlines)
        )
      case FormatToken(T.KwIf(), _, _) if leftOwner.is[Term.If] =>
        val owner = leftOwner.asInstanceOf[Term.If]
        val expire = rhsOptimalToken(
          tokens(
            owner.elsep.tokens.lastOption.getOrElse(owner.tokens.last)
          )
        )
        val elses = getElseChain(owner)
        val breakOnlyBeforeElse =
          if (elses.isEmpty) Policy.NoPolicy
          else
            Policy(expire) {
              case d @ Decision(FormatToken(_, r: T.KwElse, _), _)
                  if elses.contains(r) =>
                d.onlyNewlinesWithFallback(Split(Newline, 0))
            }
        Seq(
          Split(Space, 0)
            .withOptimalToken(expire, killOnFail = true)
            .withPolicy(SingleLineBlock(expire)),
          Split(Space, 1).withPolicy(breakOnlyBeforeElse)
        )
      case FormatToken(close: T.RightParen, _, _) if (leftOwner match {
            case _: Term.If | _: Term.For => true
            case _: Term.ForYield => style.indentYieldKeyword
            case _: Term.While => style.activeForEdition_2020_01
            case _ => false
          }) && !isFirstOrLastToken(close, leftOwner) =>
        val expire = leftOwner match {
          case t: Term.If => t.thenp.tokens.last
          case t: Term.For => t.body.tokens.last
          case t: Term.ForYield => t.body.tokens.last
          case t: Term.While => t.body.tokens.last
        }
        val exclude =
          insideBlock(formatToken, expire, _.is[T.LeftBrace]).map(parensRange)
        Seq(
          Split(Space, 0)
            .notIf(isSingleLineComment(formatToken.right) || newlines != 0)
            .withPolicy(SingleLineBlock(expire, exclude = exclude)),
          Split(Newline, 1).withIndent(2, expire, Left)
        )
      case FormatToken(T.RightBrace(), T.KwElse(), _) =>
        val nlOnly = style.newlines.alwaysBeforeElseAfterCurlyIf ||
          !leftOwner.is[Term.Block] || !leftOwner.parent.forall(_ == rightOwner)
        Seq(
          Split(Space.orNL(!nlOnly), 0)
        )

      case FormatToken(T.RightBrace(), T.KwYield(), _) =>
        Seq(
          Split(Space, 0)
        )
      case FormatToken(_, T.KwElse() | T.KwYield(), _) =>
        val expire = rhsOptimalToken(tokens(rightOwner.tokens.last))
        val exclude =
          insideBlock(formatToken, expire, _.is[T.LeftBrace]).map(parensRange)
        Seq(
          Split(Space, 0)
            .onlyIf(newlines == 0)
            .withPolicy(SingleLineBlock(expire, exclude = exclude)),
          Split(Newline, 1)
        )
      // Last else branch
      case FormatToken(_: T.KwElse, _, _) if (leftOwner match {
            case t: Term.If => !t.elsep.is[Term.If]
            case x => throw new UnexpectedTree[Term.If](x)
          }) =>
        val expire = leftOwner.asInstanceOf[Term.If].elsep.tokens.last
        Seq(
          Split(Space, 0)
            .onlyIf(newlines == 0)
            .withPolicy(SingleLineBlock(expire)),
          Split(Newline, 1).withIndent(2, expire, Left)
        )

      // Type variance
      case tok @ FormatToken(T.Ident(_), T.Ident(_) | T.Underscore(), _)
          if isTypeVariant(leftOwner) =>
        Seq(
          Split(NoSplit, 0)
        )

      // Var args
      case FormatToken(_, T.Ident("*"), _) if rightOwner.is[Type.Repeated] =>
        Seq(
          Split(NoSplit, 0)
        )

      case FormatToken(open: T.LeftParen, right, _) =>
        val isConfig = opensConfigStyle(formatToken)
        val close = matching(open)
        val breakOnClose = Policy(close) {
          case Decision(FormatToken(_, `close`, _), _) =>
            Seq(Split(Newline, 0))
        }
        val indent: Length = right match {
          case T.KwIf() => StateColumn
          case T.KwFor() if !style.indentYieldKeyword => StateColumn
          case _ => Num(0)
        }
        Seq(
          Split(Newline, 0)
            .onlyIf(isConfig)
            .withPolicy(breakOnClose)
            .withIndent(style.continuationIndent.callSite, close, Right),
          Split(NoSplit, 0)
            .notIf(isConfig)
            .withIndent(indent, close, Left)
            .withPolicy(penalizeAllNewlines(close, 1))
        )
      // Infix operator.
      case tok @ FormatToken(op @ T.Ident(_), right, between)
          if isApplyInfix(op, leftOwner) =>
        // TODO(olafur) move extractor into pattern match.
        val InfixApplication(_, op, args) = leftOwner.parent.get
        infixSplit(leftOwner, op, args, formatToken)
      case FormatToken(left, op @ T.Ident(_), between)
          if isApplyInfix(op, rightOwner) =>
        val InfixApplication(_, op, args) = rightOwner.parent.get
        infixSplit(rightOwner, op, args, formatToken)

      // Case
      case tok @ FormatToken(cs @ T.KwCase(), _, _) if leftOwner.is[Case] =>
        val owner = leftOwner.asInstanceOf[Case]
        val arrow = getCaseArrow(owner).left
        // TODO(olafur) expire on token.end to avoid this bug.
        val expire = Option(owner.body)
          .filter(_.tokens.exists(!_.is[Trivia]))
          // edge case, if body is empty expire on arrow
          .fold(arrow)(t => getOptimalTokenFor(lastToken(t)))

        Seq(
          // Either everything fits in one line or break on =>
          Split(Space, 0)
            .withOptimalToken(expire, killOnFail = true)
            .withPolicy(SingleLineBlock(expire)),
          Split(Space, 1)
            .withPolicy(
              Policy(expire) {
                case d @ Decision(t @ FormatToken(`arrow`, right, _), _)
                    // TODO(olafur) any other corner cases?
                    if !right.isInstanceOf[T.LeftBrace] &&
                      !isAttachedSingleLineComment(t) =>
                  d.onlyNewlinesWithoutFallback
              }
            )
            .withIndent(2, expire, Left) // case body indented by 2.
            .withIndent(2, arrow, Left) // cond body indented by 4.
        )
      case tok @ FormatToken(_, cond @ T.KwIf(), _) if rightOwner.is[Case] =>
        val arrow = getCaseArrow(rightOwner.asInstanceOf[Case]).left
        val exclude =
          insideBlock(tok, arrow, _.is[T.LeftBrace]).map(parensRange)
        val singleLine = SingleLineBlock(arrow, exclude = exclude)

        Seq(
          Split(Space, 0, policy = singleLine),
          Split(Newline, 1).withPolicy(penalizeNewlineByNesting(cond, arrow))
        )
      // Inline comment
      case FormatToken(_, c: T.Comment, _) =>
        Seq(Split(newlines2Modification(newlines), 0))
      // Commented out code should stay to the left
      case FormatToken(c: T.Comment, _, _) if isSingleLineComment(c) =>
        Seq(Split(Newline, 0))
      case FormatToken(c: T.Comment, _, _) =>
        Seq(Split(newlines2Modification(newlines), 0))

      case FormatToken(_: T.KwImplicit, _, _)
          if style.activeForEdition_2020_03 &&
            !style.verticalMultiline.atDefnSite =>
        opensImplicitParamList(prevNonComment(prev(formatToken))).fold {
          Seq(Split(Space, 0))
        } { params =>
          val spaceSplit = Split(Space, 0)
            .notIf(style.newlines.afterImplicitParamListModifier)
            .withPolicy(SingleLineBlock(params.last.tokens.last))
          Seq(
            spaceSplit,
            Split(Newline, if (spaceSplit.isActive) 1 else 0)
          )
        }

      case opt
          if style.optIn.annotationNewlines &&
            optionalNewlines(hash(opt.right)) =>
        Seq(Split(newlines2Modification(newlines), 0))

      // Pat
      case tok @ FormatToken(T.Ident("|"), _, _)
          if leftOwner.is[Pat.Alternative] =>
        Seq(
          Split(Space, 0),
          Split(Newline, 1)
        )
      case FormatToken(
          T.Ident(_) | Literal() | T.Interpolation.End() | T.Xml.End(),
          T.Ident(_) | Literal() | T.Xml.Start(),
          _
          ) =>
        Seq(
          Split(Space, 0)
        )

      // Case
      case FormatToken(_, T.KwMatch(), _) =>
        Seq(
          Split(Space, 0)
        )

      // Protected []
      case tok @ FormatToken(_, T.LeftBracket(), _)
          if isModPrivateProtected(leftOwner) =>
        Seq(
          Split(NoSplit, 0)
        )
      case tok @ FormatToken(T.LeftBracket(), _, _)
          if isModPrivateProtected(leftOwner) =>
        Seq(
          Split(NoSplit, 0)
        )

      // Term.ForYield
      case tok @ FormatToken(_, arrow @ T.KwIf(), _)
          if rightOwner.is[Enumerator.Guard] =>
        Seq(
          // Either everything fits in one line or break on =>
          Split(Space, 0).onlyIf(newlines == 0),
          Split(Newline, 1)
        )
      case tok @ FormatToken(arrow @ T.LeftArrow(), _, _)
          if leftOwner.is[Enumerator.Generator] =>
        val lastToken = leftOwner.tokens.last
        val indent: Length =
          if (style.align.arrowEnumeratorGenerator) StateColumn
          else Num(0)
        Seq(
          // Either everything fits in one line or break on =>
          Split(Space, 0).withIndent(indent, lastToken, Left)
        )
      case FormatToken(T.KwYield(), _, _) if leftOwner.is[Term.ForYield] =>
        if (style.newlines.avoidAfterYield && !rightOwner.is[Term.If]) {
          Seq(Split(Space, 0))
        } else {
          val lastToken = leftOwner.asInstanceOf[Term.ForYield].body.tokens.last
          Seq(
            // Either everything fits in one line or break on =>
            Split(Space, 0).withPolicy(SingleLineBlock(lastToken)),
            Split(Newline, 1).withIndent(2, lastToken, Left)
          )
        }
      // Interpolation
      case FormatToken(_, T.Interpolation.Id(_) | T.Xml.Start(), _) =>
        Seq(
          Split(Space, 0)
        )
      case FormatToken(T.Interpolation.Id(_) | T.Xml.Start(), _, _) =>
        Seq(
          Split(NoSplit, 0)
        )
      // Throw exception
      case FormatToken(T.KwThrow(), _, _) =>
        Seq(
          Split(Space, 0)
        )

      // Singleton types
      case FormatToken(_, T.KwType(), _) if rightOwner.is[Type.Singleton] =>
        Seq(
          Split(NoSplit, 0)
        )
      // seq to var args foo(seq:_*)
      case FormatToken(T.Colon(), T.Underscore(), _)
          if next(formatToken).right.syntax == "*" =>
        Seq(
          Split(Space, 0)
        )
      case FormatToken(T.Underscore(), asterisk @ T.Ident("*"), _)
          if prev(formatToken).left.is[T.Colon] =>
        Seq(
          Split(NoSplit, 0)
        )
      // Xml
      case FormatToken(T.Xml.Part(_), _, _) =>
        Seq(
          Split(NoSplit, 0)
        )
      case FormatToken(_, T.Xml.Part(_), _) =>
        Seq(
          Split(NoSplit, 0)
        )
      // Fallback
      case FormatToken(_, T.Dot(), _) =>
        Seq(
          Split(NoSplit, 0)
        )
      case FormatToken(left, T.Hash(), _) =>
        Seq(
          Split(Space(endsWithSymbolIdent(left)), 0)
        )
      case FormatToken(T.Hash(), ident: T.Ident, _) =>
        Seq(
          Split(Space(isSymbolicIdent(ident)), 0)
        )
      case FormatToken(T.Dot(), T.Ident(_) | T.KwThis() | T.KwSuper(), _) =>
        Seq(
          Split(NoSplit, 0)
        )
      case FormatToken(_, T.RightBracket(), _) =>
        Seq(
          Split(NoSplit, 0)
        )
      case FormatToken(_, T.RightParen(), _) =>
        val mod =
          Space(style.spaces.inParentheses && isDefnOrCallSite(rightOwner))
        Seq(
          Split(mod, 0)
        )

      case FormatToken(left, _: T.KwCatch | _: T.KwFinally, _)
          if style.newlinesBetweenCurlyAndCatchFinally
            || !left.is[T.RightBrace] =>
        Seq(
          Split(Newline, 0)
        )

      case FormatToken(_, Keyword(), _) =>
        Seq(
          Split(Space, 0)
        )

      case FormatToken(Keyword() | Modifier(), _, _) =>
        Seq(
          Split(Space, 0)
        )
      case FormatToken(T.LeftBracket(), _, _) =>
        Seq(
          Split(NoSplit, 0)
        )
      case FormatToken(_, Delim(), _) =>
        Seq(
          Split(Space, 0)
        )
      case FormatToken(T.Underscore(), T.Ident("*"), _) =>
        Seq(
          Split(NoSplit, 0)
        )
      case FormatToken(T.RightArrow(), _, _) if leftOwner.is[Type.ByName] =>
        val mod = Space(style.spaces.inByNameTypes)
        Seq(
          Split(mod, 0)
        )
      case FormatToken(Delim(), _, _) =>
        Seq(
          Split(Space, 0)
        )
      case tok =>
        logger.debug("MISSING CASE:\n" + log(tok))
        Seq() // No solution available, partially format tree.
    }
  }

  // TODO(olafur) replace cache with array of seq[split]
  private val cache = mutable.Map.empty[FormatToken, Seq[Split]]

  /**
    * Assigns possible splits to a FormatToken.
    *
    * The FormatToken can be considered as a node in a graph and the
    * splits as edges. Given a format token (a node in the graph), Route
    * determines which edges lead out from the format token.
    */
  def getSplitsMemo(formatToken: FormatToken): Seq[Split] =
    cache.getOrElseUpdate(
      formatToken, {
        val splits =
          getSplits(formatToken).filter(!_.isIgnored).map(_.adapt(formatToken))
        formatToken match {
          // TODO(olafur) refactor into "global policy"
          // Only newlines after inline comments.
          case FormatToken(c: T.Comment, _, _) if isSingleLineComment(c) =>
            val newlineSplits = splits.filter(_.modification.isNewline)
            if (newlineSplits.isEmpty) Seq(Split(Newline, 0))
            else newlineSplits
          case FormatToken(_, c: T.Comment, _)
              if isAttachedSingleLineComment(formatToken) =>
            splits.map(x =>
              if (x.modification.isNewline) x.copy(modification = Space)
              else x
            )
          case _ => splits
        }
      }
    )

  private implicit def int2num(n: Int): Num = Num(n)

  private def splitWithChain(
      isFirstWith: Boolean,
      chain: => Set[Tree],
      lastToken: => Token
  ): Seq[Split] =
    if (isFirstWith) {
      binPackParentConstructorSplits(chain, lastToken, IndentForWithChains)
    } else {
      Seq(Split(Space, 0), Split(Newline, 1))
    }
}
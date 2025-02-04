package dotty.tools
package dotc
package reporting

import core.Contexts._
import util.{SourcePosition, NoSourcePosition}
import core.Decorators.PhaseListDecorator
import collection.mutable
import java.lang.System.currentTimeMillis
import core.Mode
import dotty.tools.dotc.core.Symbols.Symbol
import diagnostic.messages._
import diagnostic._
import Message._

object Reporter {
  /** Convert a SimpleReporter into a real Reporter */
  def fromSimpleReporter(simple: interfaces.SimpleReporter): Reporter =
    new Reporter with UniqueMessagePositions with HideNonSensicalMessages {
      override def doReport(m: MessageContainer)(implicit ctx: Context): Unit = m match {
        case m: ConditionalWarning if !m.enablingOption.value =>
        case _ =>
          simple.report(m)
      }
    }
}

import Reporter._

trait Reporting { this: Context =>

  /** For sending messages that are printed only if -verbose is set */
  def inform(msg: => String, pos: SourcePosition = NoSourcePosition): Unit =
    if (this.settings.verbose.value) this.echo(msg, pos)

  def echo(msg: => String, pos: SourcePosition = NoSourcePosition): Unit =
    reporter.report(new Info(msg, pos))

  def reportWarning(warning: Warning): Unit =
    if (!this.settings.silentWarnings.value) {
      if (this.settings.XfatalWarnings.value) reporter.report(warning.toError)
      else  reporter.report(warning)
    }

  def deprecationWarning(msg: => Message, pos: SourcePosition = NoSourcePosition): Unit =
    reportWarning(new DeprecationWarning(msg, pos))

  def migrationWarning(msg: => Message, pos: SourcePosition = NoSourcePosition): Unit =
    reportWarning(new MigrationWarning(msg, pos))

  def uncheckedWarning(msg: => Message, pos: SourcePosition = NoSourcePosition): Unit =
    reportWarning(new UncheckedWarning(msg, pos))

  def featureWarning(msg: => Message, pos: SourcePosition = NoSourcePosition): Unit =
    reportWarning(new FeatureWarning(msg, pos))

  def featureWarning(feature: String, featureDescription: String, isScala2Feature: Boolean,
      featureUseSite: Symbol, required: Boolean, pos: SourcePosition): Unit = {
    val req = if (required) "needs to" else "should"
    val prefix = if (isScala2Feature) "scala." else "dotty."
    val fqname = prefix + "language." + feature

    val explain = {
      if (reporter.isReportedFeatureUseSite(featureUseSite)) ""
      else {
        reporter.reportNewFeatureUseSite(featureUseSite)
        s"""
           |This can be achieved by adding the import clause 'import $fqname'
           |or by setting the compiler option -language:$feature.
           |See the Scala docs for value $fqname for a discussion
           |why the feature $req be explicitly enabled.""".stripMargin
      }
    }

    val msg = s"$featureDescription $req be enabled\nby making the implicit value $fqname visible.$explain"
    if (required) error(msg, pos)
    else reportWarning(new FeatureWarning(msg, pos))
  }

  def warning(msg: => Message, pos: SourcePosition = NoSourcePosition): Unit =
    reportWarning(new Warning(msg, pos))

  def strictWarning(msg: => Message, pos: SourcePosition = NoSourcePosition): Unit =
    if (this.settings.strict.value) error(msg, pos)
    else reportWarning(new ExtendMessage(() => msg)(_ + "\n(This would be an error under strict mode)").warning(pos))

  def error(msg: => Message, pos: SourcePosition = NoSourcePosition): Unit =
    reporter.report(new Error(msg, pos))

  def errorOrMigrationWarning(msg: => Message, pos: SourcePosition = NoSourcePosition): Unit =
    if (ctx.scala2Mode) migrationWarning(msg, pos) else error(msg, pos)

  def restrictionError(msg: => Message, pos: SourcePosition = NoSourcePosition): Unit =
    reporter.report {
      new ExtendMessage(() => msg)(m => s"Implementation restriction: $m").error(pos)
    }

  def incompleteInputError(msg: => Message, pos: SourcePosition = NoSourcePosition)(implicit ctx: Context): Unit =
    reporter.incomplete(new Error(msg, pos))(ctx)

  /** Log msg if settings.log contains the current phase.
   *  See [[config.CompilerCommand#explainAdvanced]] for the exact meaning of
   *  "contains" here.
   */
  def log(msg: => String, pos: SourcePosition = NoSourcePosition): Unit =
    if (this.settings.log.value.containsPhase(phase))
      echo(s"[log ${ctx.phasesStack.reverse.mkString(" -> ")}] $msg", pos)

  def debuglog(msg: => String): Unit =
    if (ctx.debug) log(msg)

  def informTime(msg: => String, start: Long): Unit = {
    def elapsed = s" in ${currentTimeMillis - start}ms"
    informProgress(msg + elapsed)
  }

  def informProgress(msg: => String) =
    inform("[" + msg + "]")

  def logWith[T](msg: => String)(value: T) = {
    log(msg + " " + value)
    value
  }

  def debugwarn(msg: => String, pos: SourcePosition = NoSourcePosition): Unit =
    if (this.settings.debug.value) warning(msg, pos)
}

/**
 * This interface provides methods to issue information, warning and
 * error messages.
 */
abstract class Reporter extends interfaces.ReporterResult {

  /** Report a diagnostic */
  def doReport(m: MessageContainer)(implicit ctx: Context): Unit

  /** Whether very long lines can be truncated.  This exists so important
   *  debugging information (like printing the classpath) is not rendered
   *  invisible due to the max message length.
   */
  private[this] var _truncationOK: Boolean = true
  def truncationOK = _truncationOK
  def withoutTruncating[T](body: => T): T = {
    val saved = _truncationOK
    _truncationOK = false
    try body
    finally _truncationOK = saved
  }

  type ErrorHandler = MessageContainer => Context => Unit
  private[this] var incompleteHandler: ErrorHandler = d => c => report(d)(c)
  def withIncompleteHandler[T](handler: ErrorHandler)(op: => T): T = {
    val saved = incompleteHandler
    incompleteHandler = handler
    try op
    finally incompleteHandler = saved
  }

  var errorCount = 0
  var warningCount = 0
  def hasErrors = errorCount > 0
  def hasWarnings = warningCount > 0
  private[this] var errors: List[Error] = Nil
  def allErrors = errors

  /** Have errors been reported by this reporter, or in the
   *  case where this is a StoreReporter, by an outer reporter?
   */
  def errorsReported = hasErrors

  private[this] var reportedFeaturesUseSites = Set[Symbol]()
  def isReportedFeatureUseSite(featureTrait: Symbol): Boolean = reportedFeaturesUseSites.contains(featureTrait)
  def reportNewFeatureUseSite(featureTrait: Symbol): Unit = reportedFeaturesUseSites += featureTrait

  val unreportedWarnings = new mutable.HashMap[String, Int] {
    override def default(key: String) = 0
  }

  def report(m: MessageContainer)(implicit ctx: Context): Unit =
    if (!isHidden(m)) {
      doReport(m)(ctx.addMode(Mode.Printing))
      m match {
        case m: ConditionalWarning if !m.enablingOption.value => unreportedWarnings(m.enablingOption.name) += 1
        case m: Warning => warningCount += 1
        case m: Error =>
          errors = m :: errors
          errorCount += 1
        case m: Info => // nothing to do here
        // match error if d is something else
      }
    }

  def incomplete(m: MessageContainer)(implicit ctx: Context): Unit =
    incompleteHandler(m)(ctx)

  /** Summary of warnings and errors */
  def summary: String = {
    val b = new mutable.ListBuffer[String]
    if (warningCount > 0)
      b += countString(warningCount, "warning") + " found"
    if (errorCount > 0)
      b += countString(errorCount, "error") + " found"
    for ((settingName, count) <- unreportedWarnings)
      b += s"there were $count ${settingName.tail} warning(s); re-run with $settingName for details"
    b.mkString("\n")
  }

  /** Print the summary of warnings and errors */
  def printSummary(implicit ctx: Context): Unit = {
    val s = summary
    if (s != "") ctx.echo(s)
  }

  /** Returns a string meaning "n elements". */
  protected def countString(n: Int, elements: String): String = n match {
    case 0 => "no " + elements + "s"
    case 1 => "one " + elements
    case 2 => "two " + elements + "s"
    case 3 => "three " + elements + "s"
    case 4 => "four " + elements + "s"
    case _ => n + " " + elements + "s"
  }

  /** Should this diagnostic not be reported at all? */
  def isHidden(m: MessageContainer)(implicit ctx: Context): Boolean =
    ctx.mode.is(Mode.Printing)

  /** Does this reporter contain not yet reported errors or warnings? */
  def hasPending: Boolean = false

  /** If this reporter buffers messages, remove and return all buffered messages. */
  def removeBufferedMessages(implicit ctx: Context): List[MessageContainer] = Nil

  /** Issue all error messages in this reporter to next outer one, or make sure they are written. */
  def flush()(implicit ctx: Context): Unit =
    removeBufferedMessages.foreach(ctx.reporter.report)
}

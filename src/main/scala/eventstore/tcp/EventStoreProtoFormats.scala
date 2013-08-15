package eventstore
package tcp

import scala.PartialFunction.condOpt
import util.DefaultFormats
import ReadDirection.{Backward, Forward}


/**
 * @author Yaroslav Klymko
 */
object EventStoreProtoFormats extends EventStoreProtoFormats

trait EventStoreProtoFormats extends proto.DefaultProtoFormats with DefaultFormats {

  implicit object EventWriter extends ProtoWriter[Event] {
    def toProto(x: Event) = proto.NewEvent(
      `eventId` = protoByteString(x.eventId),
      `eventType` = x.eventType,
      `dataContentType` = 0,
      `metadataContentType` = 0,
      `data` = protoByteString(x.data),
      `metadata` = protoByteStringOption(x.metadata))
  }


  implicit object EventRecordReader
    extends ProtoReader[EventRecord, proto.EventRecord](proto.EventRecord) {
    def fromProto(x: proto.EventRecord) = EventRecord(
      streamId = StreamId(x.`eventStreamId`),
      number = EventNumber.Exact(x.`eventNumber`),
      event = Event(
        eventId = uuid(x.`eventId`),
        eventType = x.`eventType`,
        data = byteString(x.`data`),
        metadata = byteString(x.`metadata`)))
  }


  implicit object ResolvedEventReader
    extends ProtoReader[ResolvedEvent, proto.ResolvedEvent](proto.ResolvedEvent) {
    def fromProto(x: proto.ResolvedEvent) = ResolvedEvent(
      eventRecord = EventRecordReader.fromProto(x.`event`),
      link = x.`link`.map(EventRecordReader.fromProto),
      position = Position(commitPosition = x.`commitPosition`, preparePosition = x.`preparePosition`))
  }


  implicit object ResolvedIndexedEventReader
    extends ProtoReader[ResolvedIndexedEvent, proto.ResolvedIndexedEvent](proto.ResolvedIndexedEvent) {
    def fromProto(x: proto.ResolvedIndexedEvent) =
      ResolvedIndexedEvent(EventRecordReader.fromProto(x.`event`), x.`link`.map(EventRecordReader.fromProto))
  }


  implicit object AppendToStreamCompletedReader
    extends ProtoReader[AppendToStreamCompleted, proto.WriteEventsCompleted](proto.WriteEventsCompleted) {
    def fromProto(x: proto.WriteEventsCompleted) = operationFailed(x.`result`) match {
      case Some(reason) => AppendToStreamFailed(reason, x.`message`)
      case None => AppendToStreamSucceed(x.`firstEventNumber`)
    }
  }


  implicit object TransactionStartCompletedReader
    extends ProtoReader[TransactionStartCompleted, proto.TransactionStartCompleted](proto.TransactionStartCompleted) {
    def fromProto(x: proto.TransactionStartCompleted) = operationFailed(x.`result`) match {
      case Some(failed) => TransactionStartFailed(failed, x.`message`)
      case None => TransactionStartSucceed(x.`transactionId`)
    }
  }


  implicit object TransactionWriteCompletedReader
    extends ProtoReader[TransactionWriteCompleted, proto.TransactionWriteCompleted](proto.TransactionWriteCompleted) {
    def fromProto(x: proto.TransactionWriteCompleted) = operationFailed(x.`result`) match {
      case Some(failed) => TransactionWriteFailed(x.`transactionId`, failed, x.`message`)
      case None => TransactionWriteSucceed(x.`transactionId`)
    }
  }


  implicit object TransactionCommitCompletedReader
    extends ProtoReader[TransactionCommitCompleted, proto.TransactionCommitCompleted](proto.TransactionCommitCompleted) {
    def fromProto(x: proto.TransactionCommitCompleted) = operationFailed(x.`result`) match {
      case Some(failed) => TransactionCommitFailed(x.`transactionId`, failed, x.`message`)
      case None => TransactionCommitSucceed(x.`transactionId`)
    }
  }


  implicit object DeleteStreamCompletedReader
    extends ProtoReader[DeleteStreamCompleted, proto.DeleteStreamCompleted](proto.DeleteStreamCompleted) {
    def fromProto(x: proto.DeleteStreamCompleted) = operationFailed(x.`result`) match {
      case Some(reason) => DeleteStreamFailed(reason, x.`message`)
      case None => DeleteStreamSucceed
    }
  }


  implicit object ReadEventCompletedReader
    extends ProtoReader[ReadEventCompleted, proto.ReadEventCompleted](proto.ReadEventCompleted) {
    import eventstore.proto.ReadEventCompleted.ReadEventResult._

    // TODO test it, what if new enum will be added in proto?
    def reason(x: EnumVal): Option[ReadEventFailed.Value] = condOpt(x) {
      case NotFound => ReadEventFailed.NotFound
      case NoStream => ReadEventFailed.NoStream
      case StreamDeleted => ReadEventFailed.StreamDeleted
      case Error => ReadEventFailed.Error
      case AccessDenied => ReadEventFailed.AccessDenied
    }

    def fromProto(x: proto.ReadEventCompleted) = reason(x.`result`) match {
      case Some(reason) => ReadEventFailed(reason, x.`error`)
      case None => ReadEventSucceed(ResolvedIndexedEventReader.fromProto(x.`event`))
    }
  }



  implicit object ReadStreamEventsWriter extends ProtoWriter[ReadStreamEvents] {
    def toProto(x: ReadStreamEvents) = proto.ReadStreamEvents(
      `eventStreamId` = x.streamId.id,
      `fromEventNumber` = x.fromEventNumber,
      `maxCount` = x.maxCount,
      `resolveLinkTos` = x.resolveLinkTos)
  }

  abstract class ReadStreamEventsCompletedReader(direction: ReadDirection.Value)
    extends ProtoReader[ReadStreamEventsCompleted, proto.ReadStreamEventsCompleted](proto.ReadStreamEventsCompleted) {
    import eventstore.proto.ReadStreamEventsCompleted.ReadStreamResult._

    // TODO test it, what if new enum will be added in proto?
    def reason(x: EnumVal) = condOpt(x) {
      case NoStream => ReadStreamEventsFailed.NoStream
      case StreamDeleted => ReadStreamEventsFailed.StreamDeleted
      case Error => ReadStreamEventsFailed.Error
      case AccessDenied => ReadStreamEventsFailed.AccessDenied
    }

    def fromProto(x: proto.ReadStreamEventsCompleted) = reason(x.`result`) match {
      case None => ReadStreamEventsSucceed(
        events = x.`events`.map(ResolvedIndexedEventReader.fromProto).toList,
        nextEventNumber = /*EventNumber*/ (x.`nextEventNumber`),
        lastEventNumber = /*EventNumber*/ (x.`lastEventNumber`),
        modified = { println(x.`result`); x.`result` != NotModified},
        endOfStream = x.`isEndOfStream`,
        lastCommitPosition = x.`lastCommitPosition`,
        direction = direction)

      case Some(reason) => ReadStreamEventsFailed(
        reason = reason,
        message = x.`error`,
        nextEventNumber = /*EventNumber*/ (x.`nextEventNumber`),
        lastEventNumber = /*EventNumber*/ (x.`lastEventNumber`),
        isEndOfStream = x.`isEndOfStream`,
        lastCommitPosition = x.`lastCommitPosition`,
        direction = direction)
    }
  }

  object ReadStreamEventsForwardCompletedReader extends ReadStreamEventsCompletedReader(Forward)
  object ReadStreamEventsBackwardCompletedReader extends ReadStreamEventsCompletedReader(Backward)


  implicit object ReadAllEventsWriter extends ProtoWriter[ReadAllEvents] {
    def toProto(x: ReadAllEvents) = proto.ReadAllEvents(
      `commitPosition` = x.position.commitPosition,
      `preparePosition` = x.position.preparePosition,
      `maxCount` = x.maxCount,
      `resolveLinkTos` = x.resolveLinkTos,
      `requireMaster` = x.requireMaster)
  }


  abstract class ReadAllEventsCompletedReader(direction: ReadDirection.Value)
    extends ProtoReader[ReadAllEventsCompleted, proto.ReadAllEventsCompleted](proto.ReadAllEventsCompleted) {
    import proto.ReadAllEventsCompleted.ReadAllResult._

    // TODO test it, what if new enum will be added in proto?
    def reason(x: EnumVal): Option[ReadAllEventsFailed.Value] = condOpt(x) {
      case Error        => ReadAllEventsFailed.Error
      case AccessDenied => ReadAllEventsFailed.AccessDenied
    }

    def fromProto(x: proto.ReadAllEventsCompleted) = {
      val result = x.`result` getOrElse Success
      val position = Position(commitPosition = x.`commitPosition`, preparePosition = x.`preparePosition`)
      reason(result) match {
        case None => ReadAllEventsSucceed(
          position = position,
          resolvedEvents = x.`events`.toList.map(ResolvedEventReader.fromProto),
          nextPosition = Position(commitPosition = x.`nextCommitPosition`, preparePosition = x.`nextPreparePosition`),
          modified = result != NotModified,
          direction = direction)

        case Some(reason) => ReadAllEventsFailed(
          reason = reason,
          position = position,
          direction = direction,
          message = x.`error`)
      }
    }
  }

  object ReadAllEventsForwardCompletedReader extends ReadAllEventsCompletedReader(Forward)
  object ReadAllEventsBackwardCompletedReader extends ReadAllEventsCompletedReader(Backward)


  implicit object SubscribeCompletedReader
    extends ProtoReader[SubscribeCompleted, proto.SubscriptionConfirmation](proto.SubscriptionConfirmation) {
    def fromProto(x: proto.SubscriptionConfirmation) = x.`lastEventNumber` match {
      case None => SubscribeToAllCompleted(x.`lastCommitPosition`)
      case Some(eventNumber) => SubscribeToStreamCompleted(x.`lastCommitPosition`, EventNumber(eventNumber))
    }
  }


  implicit object StreamEventAppearedReader
    extends ProtoReader[StreamEventAppeared, proto.StreamEventAppeared](proto.StreamEventAppeared) {
    def fromProto(x: proto.StreamEventAppeared) =
      StreamEventAppeared(resolvedEvent = ResolvedEventReader.fromProto(x.`event`))
  }


  implicit object SubscriptionDroppedReader
    extends ProtoReader[SubscriptionDropped, proto.SubscriptionDropped](proto.SubscriptionDropped) {
    import eventstore.proto.SubscriptionDropped.SubscriptionDropReason._
    val default = SubscriptionDropped.Unsubscribed

    // TODO test it, what if new enum will be added in proto?
    def reason(x: EnumVal): SubscriptionDropped.Value = x match {
      case Unsubscribed => SubscriptionDropped.Unsubscribed
      case AccessDenied => SubscriptionDropped.AccessDenied
      case _ => default
    }

    def fromProto(x: proto.SubscriptionDropped) = SubscriptionDropped(reason = x.`reason`.fold(default)(reason))
  }


  implicit object AppendToStreamWriter extends ProtoWriter[AppendToStream] {
    def toProto(x: AppendToStream) = proto.WriteEvents(
      `eventStreamId` = x.streamId.id,
      `expectedVersion` = x.expVer.value,
      `events` = x.events.map(EventWriter.toProto).toVector,
      `requireMaster` = x.requireMaster)
  }


  implicit object TransactionStartWriter extends ProtoWriter[TransactionStart] {
    def toProto(x: TransactionStart) = proto.TransactionStart(
      `eventStreamId` = x.streamId.id,
      `expectedVersion` = x.expVer.value,
      `requireMaster` = x.requireMaster)
  }


  implicit object TransactionWriteWriter extends ProtoWriter[TransactionWrite] {
    def toProto(x: TransactionWrite) = proto.TransactionWrite(
      `transactionId` = x.transactionId,
      `events` = x.events.map(EventWriter.toProto).toVector,
      `requireMaster` = x.requireMaster)
  }


  implicit object TransactionCommitWriter extends ProtoWriter[TransactionCommit] {
    def toProto(x: TransactionCommit) = proto.TransactionCommit(
      `transactionId` = x.transactionId,
      `requireMaster` = x.requireMaster)
  }


  implicit object DeleteStreamWriter extends ProtoWriter[DeleteStream] {
    def toProto(x: DeleteStream) = proto.DeleteStream(
      `eventStreamId` = x.streamId.id,
      `expectedVersion` = x.expVer.value,
      `requireMaster` = x.requireMaster)
  }


  implicit object ReadEventWriter extends ProtoWriter[ReadEvent] {
    def toProto(x: ReadEvent) = {
      val eventNumber = x.eventNumber match {
        case EventNumber.Last => -1
        case EventNumber.Exact(x) => x
      }
      proto.ReadEvent(
        `eventStreamId` = x.streamId.id,
        `eventNumber` = eventNumber,
        `resolveLinkTos` = x.resolveLinkTos)
    }
  }


  implicit object SubscribeToWriter extends ProtoWriter[SubscribeTo] {
    def toProto(x: SubscribeTo) = {
      val streamId = x.stream match {
        case AllStreams => ""
        case StreamId(value) => value
      }
      proto.SubscribeToStream(
        `eventStreamId` = streamId,
        `resolveLinkTos` = x.resolveLinkTos)
    }
  }


  private def operationFailed(x: proto.OperationResult.EnumVal): Option[OperationFailed.Value] = {
    import eventstore.proto.OperationResult._
    // TODO test it, what if new enum will be added in proto?
    condOpt(x) { // TODO add plugin to align this way
      case PrepareTimeout       => OperationFailed.PrepareTimeout
      case CommitTimeout        => OperationFailed.CommitTimeout
      case ForwardTimeout       => OperationFailed.ForwardTimeout
      case WrongExpectedVersion => OperationFailed.WrongExpectedVersion
      case StreamDeleted        => OperationFailed.StreamDeleted
      case InvalidTransaction   => OperationFailed.InvalidTransaction
      case AccessDenied         => OperationFailed.AccessDenied
    }
  }
}


package ee.cone.c4actor

import java.util.Collections.singletonMap
import java.util.UUID
import java.util.concurrent.{CompletableFuture, Future}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import ee.cone.c4actor.QProtocol.{Leader, Updates}
import ee.cone.c4assemble.Single
import ee.cone.c4assemble.Types.World
import org.apache.kafka.clients.producer.{KafkaProducer, Producer, ProducerRecord, RecordMetadata}
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.clients.consumer.{Consumer, ConsumerRecord, KafkaConsumer, OffsetAndMetadata}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer

import scala.annotation.tailrec
import scala.collection.JavaConverters.seqAsJavaListConverter
import scala.collection.JavaConverters.mapAsJavaMapConverter
import scala.collection.JavaConverters.iterableAsScalaIterableConverter
import scala.collection.JavaConverters.mapAsScalaMapConverter
import scala.collection.immutable.{Map, Queue}

class KafkaRawQSender(bootstrapServers: String, inboxTopicPrefix: String)(
  producer: CompletableFuture[Producer[Array[Byte], Array[Byte]]] = new CompletableFuture()
) extends RawQSender with Executable {
  def run(ctx: ExecutionContext): Unit = {
    val props = Map[String, Object](
      //"max.request.size" → "10000000",
      "bootstrap.servers" → bootstrapServers,
      "acks" → "all",
      "retries" → "0",
      "batch.size" → "16384",
      "linger.ms" → "1",
      "buffer.memory" → "33554432"
    )
    val serializer = new ByteArraySerializer
    producer.complete(new KafkaProducer[Array[Byte], Array[Byte]](
      props.asJava, serializer, serializer
    ))
    ctx.onShutdown("Producer",() ⇒ producer.get.close())
  }
  def topicNameToString(topicName: TopicName): String = topicName match {
    case InboxTopicName() ⇒ s"$inboxTopicPrefix.inbox"
    case StateTopicName(ActorName(n)) ⇒ s"$n.state"
    case NoTopicName ⇒ throw new Exception
  }
  def sendStart(rec: QRecord): Future[RecordMetadata] = {
    //println(s"sending to server [$bootstrapServers] topic [${topicNameToString(rec.topic)}]")
    producer.get.send(new ProducerRecord(topicNameToString(rec.topic), 0, rec.key, rec.value))
  }
  def send(rec: QRecord): Long = sendStart(rec).get().offset()
}

////

class KafkaQConsumerRecordAdapter(topicName: TopicName, rec: ConsumerRecord[Array[Byte], Array[Byte]]) extends QRecord {
  def topic: TopicName = topicName
  def key: Array[Byte] = rec.key
  def value: Array[Byte] = rec.value
  def offset: Option[Long] = Option(rec.offset)
  //rec.timestamp()
}

class KafkaActor(bootstrapServers: String, actorName: ActorName)(
    qMessages: QMessages, reducer: Reducer, rawQSender: KafkaRawQSender, initialObservers: List[Observer]
)(
  alive: AtomicBoolean = new AtomicBoolean(true)
) extends Executable {
  private def iterator(consumer: Consumer[Array[Byte], Array[Byte]]) = Iterator.continually{
    if(Thread.interrupted || !alive.get) throw new InterruptedException
    consumer.poll(200 /*timeout*/).asScala
      .map(new KafkaQConsumerRecordAdapter(NoTopicName, _)).toList
  }
  type BConsumer = Consumer[Array[Byte], Array[Byte]]
  private def initConsumer(ctx: ExecutionContext): BConsumer = {
    val deserializer = new ByteArrayDeserializer
    val props: Map[String, Object] = Map(
      "bootstrap.servers" → bootstrapServers,
      "enable.auto.commit" → "false"
      //"group.id" → actorName.value //?pos
    )
    val consumer = new KafkaConsumer[Array[Byte], Array[Byte]](
      props.asJava, deserializer, deserializer
    )
    ctx.onShutdown("Consumer",() ⇒ {
      alive.set(false)
      consumer.wakeup()
    })
    consumer
  }
  private def recoverWorld(consumer: BConsumer, part: List[TopicPartition], topicName: TopicName): AtomicReference[World] = {
    LEvent.delete(Updates("",Nil)).foreach(ev⇒
      rawQSender.send(qMessages.toRecord(topicName,qMessages.toUpdate(ev)))
    ) //! prevents hanging on empty topic
    val until = Single(consumer.endOffsets(part.asJava).asScala.values.toList)
    consumer.seekToBeginning(part.asJava)

    val recsIterator = iterator(consumer).flatten
    @tailrec def toQueue(queue: Queue[QRecord]): Queue[QRecord] = {
      val rec = recsIterator.next()
      val offset = rec.offset.get + 1
      if(offset % 100000 == 0) println(offset)
      if(offset >= until) queue.enqueue(rec) else toQueue(queue.enqueue(rec))
    }
    val zeroRecord = qMessages.offsetUpdate(0L).map(qMessages.toRecord(topicName,_))
    val recsQueue = toQueue(Queue(zeroRecord:_*))
    val recsList = recsQueue.toList
    new AtomicReference(reducer.reduceRecover(reducer.createWorld(Map()), recsList))
  }
  private def startIncarnation(world:  World): World⇒Boolean = {
    val local = reducer.createTx(world)(Map())
    val leader = Leader(actorName.value,UUID.randomUUID.toString)
    LEvent.add(LEvent.update(leader)).andThen(qMessages.send)(local)
    world ⇒ By.srcId(classOf[Leader]).of(world).getOrElse(actorName.value,Nil).contains(leader)
  }

  def run(ctx: ExecutionContext): Unit = {
    val consumer = initConsumer(ctx)
    try {
      val inboxTopicName = InboxTopicName()
      val stateTopicName = StateTopicName(actorName)
      val inboxTopicPartition = List(new TopicPartition(rawQSender.topicNameToString(inboxTopicName), 0))
      val stateTopicPartition = List(new TopicPartition(rawQSender.topicNameToString(stateTopicName), 0))
      consumer.assign((inboxTopicPartition ::: stateTopicPartition).asJava)
      consumer.pause(inboxTopicPartition.asJava)
      println("starting world recover...")
      val localWorldRef = recoverWorld(consumer, stateTopicPartition, stateTopicName)
      val startOffset = qMessages.worldOffset(localWorldRef.get)
      println(s"world recovered; server [$bootstrapServers] inbox [${rawQSender.topicNameToString(inboxTopicName)}] from offset [$startOffset]")
      println(WorldStats.make(localWorldRef.get))
      consumer.seek(Single(inboxTopicPartition),startOffset)
      consumer.pause(stateTopicPartition.asJava)
      consumer.resume(inboxTopicPartition.asJava)
      val checkIncarnation = startIncarnation(localWorldRef.get)
      val observerContext = new ObserverContext(ctx, ()⇒localWorldRef.get)
      iterator(consumer).scanLeft(initialObservers){ (prevObservers, recs) ⇒
        recs match {
          case Nil ⇒ ()
            //println("no new data")
          case inboxRecs ⇒
            //println(s"offset received: ${inboxRecs.map(_.offset)}")
            val(world,queue) = reducer.reduceReceive(actorName, localWorldRef.get, inboxRecs)
            val metadata = queue.map(rawQSender.sendStart)
            metadata.foreach(_.get())
            localWorldRef.set(world)
        }
        //println(s"then to receive: ${qMessages.worldOffset(localWorldRef.get)}")
        if(checkIncarnation(localWorldRef.get))
            prevObservers.flatMap(_.activate(observerContext))
        else prevObservers
      }.foreach(_⇒())
    } finally {
      consumer.close()
    }
  }
}


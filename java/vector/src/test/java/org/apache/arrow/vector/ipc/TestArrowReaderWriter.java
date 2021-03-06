/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.arrow.vector.ipc;

import static java.nio.channels.Channels.newChannel;
import static java.util.Arrays.asList;
import static org.apache.arrow.vector.TestUtils.newVarCharVector;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import org.apache.arrow.flatbuf.FieldNode;
import org.apache.arrow.flatbuf.Message;
import org.apache.arrow.flatbuf.RecordBatch;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.util.Collections2;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.NullVector;
import org.apache.arrow.vector.TestUtils;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorLoader;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.VectorUnloader;
import org.apache.arrow.vector.dictionary.Dictionary;
import org.apache.arrow.vector.dictionary.DictionaryEncoder;
import org.apache.arrow.vector.dictionary.DictionaryProvider;
import org.apache.arrow.vector.ipc.message.ArrowBlock;
import org.apache.arrow.vector.ipc.message.ArrowDictionaryBatch;
import org.apache.arrow.vector.ipc.message.ArrowFieldNode;
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch;
import org.apache.arrow.vector.ipc.message.IpcOption;
import org.apache.arrow.vector.ipc.message.MessageSerializer;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.DictionaryEncoding;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.arrow.vector.util.ByteArrayReadableSeekableByteChannel;
import org.apache.arrow.vector.util.DictionaryUtility;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.netty.buffer.ArrowBuf;

public class TestArrowReaderWriter {

  private BufferAllocator allocator;

  private Dictionary dictionary;
  private Dictionary dictionary2;

  private Schema schema;
  private Schema encodedSchema;

  @Before
  public void init() {
    allocator = new RootAllocator(Long.MAX_VALUE);

    VarCharVector dictionary1Vector = newVarCharVector("D1", allocator);
    dictionary1Vector.allocateNewSafe();
    dictionary1Vector.set(0, "foo".getBytes(StandardCharsets.UTF_8));
    dictionary1Vector.set(1, "bar".getBytes(StandardCharsets.UTF_8));
    dictionary1Vector.set(2, "baz".getBytes(StandardCharsets.UTF_8));
    dictionary1Vector.setValueCount(3);

    dictionary = new Dictionary(dictionary1Vector, new DictionaryEncoding(1L, false, null));

    VarCharVector dictionary2Vector = newVarCharVector("D2", allocator);
    dictionary2Vector.allocateNewSafe();
    dictionary2Vector.set(0, "aa".getBytes(StandardCharsets.UTF_8));
    dictionary2Vector.set(1, "bb".getBytes(StandardCharsets.UTF_8));
    dictionary2Vector.set(2, "cc".getBytes(StandardCharsets.UTF_8));
    dictionary2Vector.setValueCount(3);

    dictionary2 = new Dictionary(dictionary2Vector, new DictionaryEncoding(2L, false, null));
  }

  @After
  public void terminate() throws Exception {
    dictionary.getVector().close();
    dictionary2.getVector().close();
    allocator.close();
  }

  ArrowBuf buf(byte[] bytes) {
    ArrowBuf buffer = allocator.buffer(bytes.length);
    buffer.writeBytes(bytes);
    return buffer;
  }

  byte[] array(ArrowBuf buf) {
    byte[] bytes = new byte[buf.readableBytes()];
    buf.readBytes(bytes);
    return bytes;
  }

  @Test
  public void test() throws IOException {
    Schema schema = new Schema(asList(new Field("testField", FieldType.nullable(new ArrowType.Int(8, true)),
        Collections.<Field>emptyList())));
    ArrowType type = schema.getFields().get(0).getType();
    FieldVector vector = TestUtils.newVector(FieldVector.class, "testField", type, allocator);
    vector.initializeChildrenFromFields(schema.getFields().get(0).getChildren());

    byte[] validity = new byte[] {(byte) 255, 0};
    // second half is "undefined"
    byte[] values = new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (VectorSchemaRoot root = new VectorSchemaRoot(schema.getFields(), asList(vector), 16);
         ArrowFileWriter writer = new ArrowFileWriter(root, null, newChannel(out))) {
      ArrowBuf validityb = buf(validity);
      ArrowBuf valuesb = buf(values);
      ArrowRecordBatch batch = new ArrowRecordBatch(16, asList(new ArrowFieldNode(16, 8)), asList(validityb, valuesb));
      VectorLoader loader = new VectorLoader(root);
      loader.load(batch);
      writer.writeBatch();

      validityb.close();
      valuesb.close();
      batch.close();
    }

    byte[] byteArray = out.toByteArray();

    try (SeekableReadChannel channel = new SeekableReadChannel(new ByteArrayReadableSeekableByteChannel(byteArray));
         ArrowFileReader reader = new ArrowFileReader(channel, allocator)) {
      Schema readSchema = reader.getVectorSchemaRoot().getSchema();
      assertEquals(schema, readSchema);
      // TODO: dictionaries
      List<ArrowBlock> recordBatches = reader.getRecordBlocks();
      assertEquals(1, recordBatches.size());
      reader.loadNextBatch();
      VectorUnloader unloader = new VectorUnloader(reader.getVectorSchemaRoot());
      ArrowRecordBatch recordBatch = unloader.getRecordBatch();
      List<ArrowFieldNode> nodes = recordBatch.getNodes();
      assertEquals(1, nodes.size());
      ArrowFieldNode node = nodes.get(0);
      assertEquals(16, node.getLength());
      assertEquals(8, node.getNullCount());
      List<ArrowBuf> buffers = recordBatch.getBuffers();
      assertEquals(2, buffers.size());
      assertArrayEquals(validity, array(buffers.get(0)));
      assertArrayEquals(values, array(buffers.get(1)));

      // Read just the header. This demonstrates being able to read without need to
      // deserialize the buffer.
      ByteBuffer headerBuffer = ByteBuffer.allocate(recordBatches.get(0).getMetadataLength());
      headerBuffer.put(byteArray, (int) recordBatches.get(0).getOffset(), headerBuffer.capacity());
      // new format prefix_size ==8
      headerBuffer.position(8);
      Message messageFB = Message.getRootAsMessage(headerBuffer);
      RecordBatch recordBatchFB = (RecordBatch) messageFB.header(new RecordBatch());
      assertEquals(2, recordBatchFB.buffersLength());
      assertEquals(1, recordBatchFB.nodesLength());
      FieldNode nodeFB = recordBatchFB.nodes(0);
      assertEquals(16, nodeFB.length());
      assertEquals(8, nodeFB.nullCount());

      recordBatch.close();
    }
  }

  @Test
  public void testWriteReadNullVector() throws IOException {

    int valueCount = 3;

    NullVector nullVector = new NullVector();
    nullVector.setValueCount(valueCount);

    Schema schema = new Schema(asList(nullVector.getField()));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (VectorSchemaRoot root = new VectorSchemaRoot(schema.getFields(), asList(nullVector), valueCount);
        ArrowFileWriter writer = new ArrowFileWriter(root, null, newChannel(out))) {
      ArrowRecordBatch batch = new ArrowRecordBatch(valueCount,
          asList(new ArrowFieldNode(valueCount, 0)),
          Collections.emptyList());
      VectorLoader loader = new VectorLoader(root);
      loader.load(batch);
      writer.writeBatch();
    }

    byte[] byteArray = out.toByteArray();

    try (SeekableReadChannel channel = new SeekableReadChannel(new ByteArrayReadableSeekableByteChannel(byteArray));
        ArrowFileReader reader = new ArrowFileReader(channel, allocator)) {
      Schema readSchema = reader.getVectorSchemaRoot().getSchema();
      assertEquals(schema, readSchema);
      List<ArrowBlock> recordBatches = reader.getRecordBlocks();
      assertEquals(1, recordBatches.size());

      assertTrue(reader.loadNextBatch());
      assertEquals(1, reader.getVectorSchemaRoot().getFieldVectors().size());

      NullVector readNullVector = (NullVector) reader.getVectorSchemaRoot().getFieldVectors().get(0);
      assertEquals(valueCount, readNullVector.getValueCount());
    }
  }

  @Test
  public void testWriteReadWithDictionaries() throws IOException {
    DictionaryProvider.MapDictionaryProvider provider = new DictionaryProvider.MapDictionaryProvider();
    provider.put(dictionary);

    VarCharVector vector1 = newVarCharVector("varchar1", allocator);
    vector1.allocateNewSafe();
    vector1.set(0, "foo".getBytes(StandardCharsets.UTF_8));
    vector1.set(1, "bar".getBytes(StandardCharsets.UTF_8));
    vector1.set(3, "baz".getBytes(StandardCharsets.UTF_8));
    vector1.set(4, "bar".getBytes(StandardCharsets.UTF_8));
    vector1.set(5, "baz".getBytes(StandardCharsets.UTF_8));
    vector1.setValueCount(6);
    FieldVector encodedVector1 = (FieldVector) DictionaryEncoder.encode(vector1, dictionary);
    vector1.close();

    VarCharVector vector2 = newVarCharVector("varchar2", allocator);
    vector2.allocateNewSafe();
    vector2.set(0, "bar".getBytes(StandardCharsets.UTF_8));
    vector2.set(1, "baz".getBytes(StandardCharsets.UTF_8));
    vector2.set(2, "foo".getBytes(StandardCharsets.UTF_8));
    vector2.set(3, "foo".getBytes(StandardCharsets.UTF_8));
    vector2.set(4, "foo".getBytes(StandardCharsets.UTF_8));
    vector2.set(5, "bar".getBytes(StandardCharsets.UTF_8));
    vector2.setValueCount(6);
    FieldVector encodedVector2 = (FieldVector) DictionaryEncoder.encode(vector2, dictionary);
    vector2.close();

    List<Field> fields = Arrays.asList(encodedVector1.getField(), encodedVector2.getField());
    List<FieldVector> vectors = Collections2.asImmutableList(encodedVector1, encodedVector2);
    try (VectorSchemaRoot root =  new VectorSchemaRoot(fields, vectors, encodedVector1.getValueCount());
         ByteArrayOutputStream out = new ByteArrayOutputStream();
         ArrowFileWriter writer = new ArrowFileWriter(root, provider, newChannel(out));) {

      writer.start();
      writer.writeBatch();
      writer.end();

      try (SeekableReadChannel channel = new SeekableReadChannel(
          new ByteArrayReadableSeekableByteChannel(out.toByteArray()));
          ArrowFileReader reader = new ArrowFileReader(channel, allocator)) {
        Schema readSchema = reader.getVectorSchemaRoot().getSchema();
        assertEquals(root.getSchema(), readSchema);
        assertEquals(1, reader.getDictionaryBlocks().size());
        assertEquals(1, reader.getRecordBlocks().size());

        reader.loadNextBatch();
        assertEquals(2, reader.getVectorSchemaRoot().getFieldVectors().size());
      }
    }
  }

  @Test
  public void testEmptyStreamInFileIPC() throws IOException {

    DictionaryProvider.MapDictionaryProvider provider = new DictionaryProvider.MapDictionaryProvider();
    provider.put(dictionary);

    VarCharVector vector = newVarCharVector("varchar", allocator);
    vector.allocateNewSafe();
    vector.set(0, "foo".getBytes(StandardCharsets.UTF_8));
    vector.set(1, "bar".getBytes(StandardCharsets.UTF_8));
    vector.set(3, "baz".getBytes(StandardCharsets.UTF_8));
    vector.set(4, "bar".getBytes(StandardCharsets.UTF_8));
    vector.set(5, "baz".getBytes(StandardCharsets.UTF_8));
    vector.setValueCount(6);

    FieldVector encodedVector1A = (FieldVector) DictionaryEncoder.encode(vector, dictionary);
    vector.close();

    List<Field> fields = Arrays.asList(encodedVector1A.getField());
    List<FieldVector> vectors = Collections2.asImmutableList(encodedVector1A);

    try (VectorSchemaRoot root =  new VectorSchemaRoot(fields, vectors, encodedVector1A.getValueCount());
         ByteArrayOutputStream out = new ByteArrayOutputStream();
         ArrowFileWriter writer = new ArrowFileWriter(root, provider, newChannel(out))) {

      writer.start();
      writer.end();

      try (SeekableReadChannel channel = new SeekableReadChannel(
           new ByteArrayReadableSeekableByteChannel(out.toByteArray()));
           ArrowFileReader reader = new ArrowFileReader(channel, allocator)) {
        Schema readSchema = reader.getVectorSchemaRoot().getSchema();
        assertEquals(root.getSchema(), readSchema);
        assertEquals(1, reader.getDictionaryVectors().size());
        assertEquals(0, reader.getDictionaryBlocks().size());
        assertEquals(0, reader.getRecordBlocks().size());
      }
    }

  }

  @Test
  public void testEmptyStreamInStreamingIPC() throws IOException {

    DictionaryProvider.MapDictionaryProvider provider = new DictionaryProvider.MapDictionaryProvider();
    provider.put(dictionary);

    VarCharVector vector = newVarCharVector("varchar", allocator);
    vector.allocateNewSafe();
    vector.set(0, "foo".getBytes(StandardCharsets.UTF_8));
    vector.set(1, "bar".getBytes(StandardCharsets.UTF_8));
    vector.set(3, "baz".getBytes(StandardCharsets.UTF_8));
    vector.set(4, "bar".getBytes(StandardCharsets.UTF_8));
    vector.set(5, "baz".getBytes(StandardCharsets.UTF_8));
    vector.setValueCount(6);

    FieldVector encodedVector = (FieldVector) DictionaryEncoder.encode(vector, dictionary);
    vector.close();

    List<Field> fields = Arrays.asList(encodedVector.getField());
    try (VectorSchemaRoot root =
        new VectorSchemaRoot(fields, Arrays.asList(encodedVector), encodedVector.getValueCount());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ArrowStreamWriter writer = new ArrowStreamWriter(root, provider, newChannel(out))) {

      writer.start();
      writer.end();


      try (ArrowStreamReader reader = new ArrowStreamReader(
          new ByteArrayReadableSeekableByteChannel(out.toByteArray()), allocator)) {
        Schema readSchema = reader.getVectorSchemaRoot().getSchema();
        assertEquals(root.getSchema(), readSchema);
        assertEquals(1, reader.getDictionaryVectors().size());
        assertFalse(reader.loadNextBatch());
      }
    }

  }

  @Test
  public void testReadInterleavedData() throws IOException {
    List<ArrowRecordBatch> batches = createRecordBatches();

    ByteArrayOutputStream outStream = new ByteArrayOutputStream();
    WriteChannel out = new WriteChannel(newChannel(outStream));

    // write schema
    MessageSerializer.serialize(out, schema);

    // write dictionary1
    FieldVector dictVector1 = dictionary.getVector();
    VectorSchemaRoot dictRoot1 = new VectorSchemaRoot(
        Collections.singletonList(dictVector1.getField()),
        Collections.singletonList(dictVector1),
        dictVector1.getValueCount());
    ArrowDictionaryBatch dictionaryBatch1 =
        new ArrowDictionaryBatch(1, new VectorUnloader(dictRoot1).getRecordBatch());
    MessageSerializer.serialize(out, dictionaryBatch1);
    dictionaryBatch1.close();
    dictRoot1.close();

    // write recordBatch1
    MessageSerializer.serialize(out, batches.get(0));

    // write dictionary2
    FieldVector dictVector2 = dictionary2.getVector();
    VectorSchemaRoot dictRoot2 = new VectorSchemaRoot(
        Collections.singletonList(dictVector2.getField()),
        Collections.singletonList(dictVector2),
        dictVector2.getValueCount());
    ArrowDictionaryBatch dictionaryBatch2 =
        new ArrowDictionaryBatch(2, new VectorUnloader(dictRoot2).getRecordBatch());
    MessageSerializer.serialize(out, dictionaryBatch2);
    dictionaryBatch2.close();
    dictRoot2.close();

    // write recordBatch1
    MessageSerializer.serialize(out, batches.get(1));

    // write eos
    out.writeIntLittleEndian(0);

    try (ArrowStreamReader reader = new ArrowStreamReader(
        new ByteArrayReadableSeekableByteChannel(outStream.toByteArray()), allocator)) {
      Schema readSchema = reader.getVectorSchemaRoot().getSchema();
      assertEquals(encodedSchema, readSchema);
      assertEquals(2, reader.getDictionaryVectors().size());
      assertTrue(reader.loadNextBatch());
      assertTrue(reader.loadNextBatch());
      assertFalse(reader.loadNextBatch());
    }

    batches.forEach(batch -> batch.close());
  }

  private List<ArrowRecordBatch> createRecordBatches() {
    List<ArrowRecordBatch> batches = new ArrayList<>();

    DictionaryProvider.MapDictionaryProvider provider = new DictionaryProvider.MapDictionaryProvider();
    provider.put(dictionary);
    provider.put(dictionary2);

    VarCharVector vectorA1 = newVarCharVector("varcharA1", allocator);
    vectorA1.allocateNewSafe();
    vectorA1.set(0, "foo".getBytes(StandardCharsets.UTF_8));
    vectorA1.set(1, "bar".getBytes(StandardCharsets.UTF_8));
    vectorA1.set(3, "baz".getBytes(StandardCharsets.UTF_8));
    vectorA1.set(4, "bar".getBytes(StandardCharsets.UTF_8));
    vectorA1.set(5, "baz".getBytes(StandardCharsets.UTF_8));
    vectorA1.setValueCount(6);

    VarCharVector vectorA2 = newVarCharVector("varcharA2", allocator);
    vectorA2.setValueCount(6);
    FieldVector encodedVectorA1 = (FieldVector) DictionaryEncoder.encode(vectorA1, dictionary);
    vectorA1.close();
    FieldVector encodedVectorA2 = (FieldVector) DictionaryEncoder.encode(vectorA1, dictionary2);
    vectorA2.close();

    List<Field> fields = Arrays.asList(encodedVectorA1.getField(), encodedVectorA2.getField());
    List<FieldVector> vectors = Collections2.asImmutableList(encodedVectorA1, encodedVectorA2);
    VectorSchemaRoot root =  new VectorSchemaRoot(fields, vectors, encodedVectorA1.getValueCount());
    VectorUnloader unloader = new VectorUnloader(root);
    batches.add(unloader.getRecordBatch());
    root.close();

    VarCharVector vectorB1 = newVarCharVector("varcharB1", allocator);
    vectorB1.setValueCount(6);

    VarCharVector vectorB2 = newVarCharVector("varcharB2", allocator);
    vectorB2.allocateNew();
    vectorB2.setValueCount(6);
    vectorB2.set(0, "aa".getBytes(StandardCharsets.UTF_8));
    vectorB2.set(1, "aa".getBytes(StandardCharsets.UTF_8));
    vectorB2.set(3, "bb".getBytes(StandardCharsets.UTF_8));
    vectorB2.set(4, "bb".getBytes(StandardCharsets.UTF_8));
    vectorB2.set(5, "cc".getBytes(StandardCharsets.UTF_8));
    vectorB2.setValueCount(6);
    FieldVector encodedVectorB1 = (FieldVector) DictionaryEncoder.encode(vectorB1, dictionary);
    vectorB1.close();
    FieldVector encodedVectorB2 = (FieldVector) DictionaryEncoder.encode(vectorB2, dictionary2);
    vectorB2.close();

    List<Field> fieldsB = Arrays.asList(encodedVectorB1.getField(), encodedVectorB2.getField());
    List<FieldVector> vectorsB = Collections2.asImmutableList(encodedVectorB1, encodedVectorB2);
    VectorSchemaRoot rootB =  new VectorSchemaRoot(fieldsB, vectorsB, 6);
    VectorUnloader unloaderB = new VectorUnloader(rootB);
    batches.add(unloaderB.getRecordBatch());
    rootB.close();

    List<Field> schemaFields = new ArrayList<>();
    schemaFields.add(DictionaryUtility.toMessageFormat(encodedVectorA1.getField(), provider, new HashSet<>()));
    schemaFields.add(DictionaryUtility.toMessageFormat(encodedVectorA2.getField(), provider, new HashSet<>()));
    schema = new Schema(schemaFields);

    encodedSchema = new Schema(Arrays.asList(encodedVectorA1.getField(), encodedVectorA2.getField()));

    return batches;
  }

  @Test
  public void testLegacyIpcBackwardsCompatibility() throws Exception {
    Schema schema = new Schema(asList(Field.nullable("field", new ArrowType.Int(32, true))));
    IntVector vector = new IntVector("vector", allocator);
    final int valueCount = 2;
    vector.setValueCount(valueCount);
    vector.setSafe(0, 1);
    vector.setSafe(1, 2);
    ArrowRecordBatch batch = new ArrowRecordBatch(valueCount, asList(new ArrowFieldNode(valueCount, 0)),
        asList(vector.getValidityBuffer(), vector.getDataBuffer()));

    ByteArrayOutputStream outStream = new ByteArrayOutputStream();
    WriteChannel out = new WriteChannel(newChannel(outStream));

    // write legacy ipc format
    IpcOption option = new IpcOption();
    option.write_legacy_ipc_format = true;
    MessageSerializer.serialize(out, schema, option);
    MessageSerializer.serialize(out, batch);

    ReadChannel in = new ReadChannel(newChannel(new ByteArrayInputStream(outStream.toByteArray())));
    Schema readSchema = MessageSerializer.deserializeSchema(in);
    assertEquals(schema, readSchema);
    ArrowRecordBatch readBatch = MessageSerializer.deserializeRecordBatch(in, allocator);
    assertEquals(batch.getLength(), readBatch.getLength());
    assertEquals(batch.computeBodyLength(), readBatch.computeBodyLength());
    readBatch.close();

    // write ipc format with continuation
    option.write_legacy_ipc_format = false;
    MessageSerializer.serialize(out, schema, option);
    MessageSerializer.serialize(out, batch);

    ReadChannel in2 = new ReadChannel(newChannel(new ByteArrayInputStream(outStream.toByteArray())));
    Schema readSchema2 = MessageSerializer.deserializeSchema(in2);
    assertEquals(schema, readSchema2);
    ArrowRecordBatch readBatch2 = MessageSerializer.deserializeRecordBatch(in2, allocator);
    assertEquals(batch.getLength(), readBatch2.getLength());
    assertEquals(batch.computeBodyLength(), readBatch2.computeBodyLength());
    readBatch2.close();

    batch.close();
    vector.close();
  }

  @Test
  public void testChannelReadFully() throws IOException {
    final ByteBuffer buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
    buf.putInt(200);
    buf.rewind();

    try (ReadChannel channel = new ReadChannel(Channels.newChannel(new ByteArrayInputStream(buf.array())));
         ArrowBuf arrBuf = allocator.buffer(8)) {
      arrBuf.setInt(0, 100);
      arrBuf.writerIndex(4);
      assertEquals(4, arrBuf.writerIndex());

      int n = channel.readFully(arrBuf, 4);
      assertEquals(4, n);
      assertEquals(8, arrBuf.writerIndex());

      assertEquals(100, arrBuf.getInt(0));
      assertEquals(200, arrBuf.getInt(4));
    }
  }

  @Test
  public void testChannelReadFullyEos() throws IOException {
    final ByteBuffer buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
    buf.putInt(10);
    buf.rewind();

    try (ReadChannel channel = new ReadChannel(Channels.newChannel(new ByteArrayInputStream(buf.array())));
         ArrowBuf arrBuf = allocator.buffer(8)) {
      int n = channel.readFully(arrBuf.nioBuffer(0, 8));
      assertEquals(4, n);

      // the input has only 4 bytes, so the number of bytes read should be 4
      assertEquals(4, channel.bytesRead());

      // the first 4 bytes have been read successfully.
      assertEquals(10, arrBuf.getInt(0));
    }
  }
}

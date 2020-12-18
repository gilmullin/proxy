package proxy;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import proxy.impl.BatchSupport;
import proxy.model.Message;
import proxy.model.ServiceResponse;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * @author rushan
 */
public class BatchSupportTest {

    public static final byte[] TEST_BODY = "Test".getBytes(StandardCharsets.UTF_8);

    private BatchSupport batchSupport;

    @Before
    public void init() {
        batchSupport = new BatchSupport();
    }

    @Test
    public void testToByteBufferEmptyMessageList() {
        byte[] buffer = batchSupport.toBatchBuffer(Collections.emptyList());
        Assert.assertEquals(0, buffer.length);
    }

    @Test
    public void testToByteBufferSingleMessage() {
        Instant time = Instant.now();
        List<Message> messages = Collections.singletonList(new Message(time, TEST_BODY));
        byte[] buffer = batchSupport.toBatchBuffer(messages);
        Assert.assertEquals(12 + TEST_BODY.length, buffer.length);

        ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
        Assert.assertEquals(byteBuffer.getLong(), time.toEpochMilli());
        Assert.assertEquals(byteBuffer.getInt(), TEST_BODY.length);
        byte[] testData = new byte[4];
        byteBuffer.get(testData);
        Assert.assertArrayEquals(testData, TEST_BODY);
        Assert.assertEquals(0, byteBuffer.remaining());
    }

    @Test
    public void testToByteBufferTwoMessages() {
        Instant time1 = Instant.now();
        Instant time2 = time1.plusMillis(1000);
        List<Message> messages = Arrays.asList(new Message(time1, TEST_BODY),
                new Message(time2, TEST_BODY));
        byte[] buffer = batchSupport.toBatchBuffer(messages);
        Assert.assertEquals(2* (12 + TEST_BODY.length), buffer.length);

        ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);

        Assert.assertEquals(byteBuffer.getLong(), time1.toEpochMilli());
        Assert.assertEquals(byteBuffer.getInt(), TEST_BODY.length);
        byte[] testData = new byte[4];
        byteBuffer.get(testData);
        Assert.assertArrayEquals(testData, TEST_BODY);

        Assert.assertEquals(byteBuffer.getLong(), time2.toEpochMilli());
        Assert.assertEquals(byteBuffer.getInt(), TEST_BODY.length);
        testData = new byte[4];
        byteBuffer.get(testData);
        Assert.assertArrayEquals(testData, TEST_BODY);

        Assert.assertEquals(0, byteBuffer.remaining());
    }

    @Test
    public void testExtractEmptyResponse() {
        List<ServiceResponse> result = batchSupport.splitBatch(ServiceResponse.empty());
        Assert.assertEquals(0, result.size());
    }

    @Test
    public void testExtractSingleResponse() {
        Instant time = Instant.now();

        ByteBuffer testBuff = ByteBuffer.allocate(4 + TEST_BODY.length);
        testBuff.putInt(TEST_BODY.length);
        testBuff.put(TEST_BODY);

        List<ServiceResponse> result = batchSupport.splitBatch(ServiceResponse.ok(testBuff.array()));

        Assert.assertEquals(1, result.size());

        Assert.assertEquals(TEST_BODY.length, result.get(0).getData().length);
        Assert.assertArrayEquals(TEST_BODY, result.get(0).getData());
    }

    @Test
    public void testExtractTwoResponse() {
        Instant time = Instant.now();

        ByteBuffer testBuff = ByteBuffer.allocate(2 * (4 + TEST_BODY.length));
        testBuff.putInt(TEST_BODY.length);
        testBuff.put(TEST_BODY);
        testBuff.putInt(TEST_BODY.length);
        testBuff.put(TEST_BODY);

        List<ServiceResponse> result = batchSupport.splitBatch(ServiceResponse.ok(testBuff.array()));

        Assert.assertEquals(2, result.size());

        Assert.assertEquals(TEST_BODY.length, result.get(0).getData().length);
        Assert.assertArrayEquals(TEST_BODY, result.get(0).getData());

        Assert.assertEquals(TEST_BODY.length, result.get(1).getData().length);
        Assert.assertArrayEquals(TEST_BODY, result.get(1).getData());
    }
}

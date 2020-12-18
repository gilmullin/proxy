package proxy.impl;

import proxy.model.Message;
import proxy.model.ServiceResponse;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Создание батчей и восстановление ответов сервера из батчей.
 *
 * @author rushan
 */
public class BatchSupport {

    /**
     * Создает из списка сообщение батч-буффер для отправки в сервис.
     */
    public byte[] toBatchBuffer(List<Message> messages) {

        if (messages.isEmpty()) {
            return new byte[0];
        }

        int initialAllocate = 0;
        for (Message m : messages) {
            // размер данных + 4 байта на размер сообщения + 8 байт на timestamp
            initialAllocate += m.getData().length + 12;
        }

        ByteBuffer buff = ByteBuffer.allocate(initialAllocate);

        for (Message message : messages) {
            byte[] data = message.getData();
            buff.putLong(message.getReceivedAt().toEpochMilli());
            buff.putInt(data.length);
            buff.put(data);
        }

        return buff.array();
    }

    /**
     * Извлекает из батч-буффера, полученного из сервиса, список объектов {@link ServiceResponse}.
     */
    public List<ServiceResponse> splitBatch(ServiceResponse batchResponse) {
        byte[] batchData = batchResponse.getData();
        int batchStatus = batchResponse.getStatus();
        ByteBuffer buffer = ByteBuffer.wrap(batchData);

        List<ServiceResponse> result = new ArrayList<>();
        while (buffer.remaining() > 0) {
            int messageLength = buffer.getInt();
            byte[] messageData = new byte[messageLength];
            buffer.get(messageData);
            result.add(ServiceResponse.response(batchStatus, messageData));
        }

        return result;
    }
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.samza.serializers;

import java.util.Arrays;

import org.apache.samza.SamzaException;
import org.apache.samza.system.DrainMessage;
import org.apache.samza.system.EndOfStreamMessage;
import org.apache.samza.system.MessageType;
import org.apache.samza.system.WatermarkMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This class provides serialization/deserialization of the intermediate messages.
 *
 * The message format of an intermediate stream is below:
 *
 * IntermediateStreamMessage: {
 *   MessageType : int8
 *   MessageData : byte[]
 * }
 *
 * MessageType: [0(UserMessage), 1(Watermark), 2(EndOfStream)]
 * MessageData: [UserMessage/ControlMessage]
 * ControlMessage:
 *   Version   : int
 *   TaskName  : string
 *   TaskCount : int
 *   Other Message Data (based on different types of control message)
 *
 * For user message, we use the user message serde.
 * For control message, we use json serde.
 */
public class IntermediateMessageSerde implements Serde<Object> {
  private static final Logger LOGGER = LoggerFactory.getLogger(IntermediateMessageSerde.class);

  private final Serde userMessageSerde;
  private final Serde<WatermarkMessage> watermarkSerde;
  private final Serde<EndOfStreamMessage> eosSerde;
  private final Serde<DrainMessage> drainMessageSerde;

  public IntermediateMessageSerde(Serde userMessageSerde) {
    this.userMessageSerde = userMessageSerde;
    this.watermarkSerde = new JsonSerdeV2<>(WatermarkMessage.class);
    this.eosSerde = new JsonSerdeV2<>(EndOfStreamMessage.class);
    this.drainMessageSerde = new JsonSerdeV2<>(DrainMessage.class);
  }

  @Override
  public Object fromBytes(byte[] bytes) {
    try {
      final Object object;
      final MessageType type;
      try {
        type = MessageType.values()[bytes[0]];
      } catch (ArrayIndexOutOfBoundsException e) {
        // The message type was introduced in samza 0.13.1. For samza 0.13.0 or older versions, the first byte of
        // MessageType doesn't exist in the bytes. Thus, upgrading from those versions will get this exception.
        // There are three ways to solve this issue:
        // a) Reset checkpoint to consume from newest message in the intermediate stream
        // b) clean all existing messages in the intermediate stream
        // c) Run the application in any version between 0.13.1 and 1.5 until all old messages in intermediate stream
        // has reached retention time.
        throw new SamzaException("Error reading the message type from intermediate message. This may happen if you "
            + "have recently upgraded from samza version older than 0.13.1 or there are still old messages in the "
            + "intermediate stream.", e);
      }
      final byte[] data = Arrays.copyOfRange(bytes, 1, bytes.length);
      switch (type) {
        case USER_MESSAGE:
          object = userMessageSerde.fromBytes(data);
          break;
        case WATERMARK:
          object = watermarkSerde.fromBytes(data);
          break;
        case END_OF_STREAM:
          object = eosSerde.fromBytes(data);
          break;
        case DRAIN:
          object = drainMessageSerde.fromBytes(data);
          break;
        default:
          throw new UnsupportedOperationException(String.format("Message type %s is not supported", type.name()));
      }
      return object;
    } catch (UnsupportedOperationException ue) {
      throw new SamzaException(ue);
    } catch (Exception e) {
      throw e;
    }
  }

  @Override
  public byte[] toBytes(Object object) {
    final byte[] data;
    final MessageType type = MessageType.of(object);
    switch (type) {
      case USER_MESSAGE:
        data = userMessageSerde.toBytes(object);
        break;
      case WATERMARK:
        data = watermarkSerde.toBytes((WatermarkMessage) object);
        break;
      case END_OF_STREAM:
        data = eosSerde.toBytes((EndOfStreamMessage) object);
        break;
      case DRAIN:
        data = drainMessageSerde.toBytes((DrainMessage) object);
        break;
      default:
        throw new SamzaException("Unknown message type: " + type.name());
    }

    final byte[] bytes = new byte[data.length + 1];
    bytes[0] = (byte) type.ordinal();
    System.arraycopy(data, 0, bytes, 1, data.length);

    return bytes;
  }
}

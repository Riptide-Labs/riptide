package org.riptide.pipeline;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * Contains all of the fields used to uniquely identify a conversation.
 */
@ToString
@EqualsAndHashCode
@Getter
@AllArgsConstructor
public class ConversationKey {

    private final String location;
    private final Integer protocol;
    private final String lowerIp;
    private final String upperIp;
    private final String application;

}

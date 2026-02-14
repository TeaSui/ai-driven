package com.aidriven.core.tracker;

import com.aidriven.core.model.TicketInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class IssueTrackerClientTest {

    @Test
    void interface_should_declare_getTicket_method() throws NoSuchMethodException {
        Method method = IssueTrackerClient.class.getMethod("getTicket", String.class);
        assertEquals(TicketInfo.class, method.getReturnType());
    }

    @Test
    void interface_should_declare_addComment_method() throws NoSuchMethodException {
        Method method = IssueTrackerClient.class.getMethod("addComment", String.class, String.class);
        assertEquals(void.class, method.getReturnType());
    }

    @Test
    void interface_should_declare_transitionTicket_method() throws NoSuchMethodException {
        Method method = IssueTrackerClient.class.getMethod("transitionTicket", String.class, String.class);
        assertEquals(void.class, method.getReturnType());
    }

    @Test
    void interface_should_declare_updateStatus_method() throws NoSuchMethodException {
        Method method = IssueTrackerClient.class.getMethod("updateStatus", String.class, String.class);
        assertEquals(void.class, method.getReturnType());
    }

    @Test
    void interface_should_have_exactly_four_methods() {
        Set<String> methods = Arrays.stream(IssueTrackerClient.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("getTicket", "addComment", "transitionTicket", "updateStatus"), methods);
    }

}

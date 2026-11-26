package com.milktea.order.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

class GlobalExceptionHandlerTest {

    @Test
    void unavailableAsyncResponseDoesNotReceiveJsonErrorBody() throws Exception {
        MockMvcBuilders.standaloneSetup(new DisconnectedStream())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build()
                .perform(get("/disconnected-stream"))
                .andExpect(content().string(""));
    }

    @RestController
    static class DisconnectedStream {
        @GetMapping("/disconnected-stream")
        void disconnect() throws AsyncRequestNotUsableException {
            throw new AsyncRequestNotUsableException("Client disconnected");
        }
    }
}

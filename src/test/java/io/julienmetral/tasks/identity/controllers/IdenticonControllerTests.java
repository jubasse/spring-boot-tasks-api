package io.julienmetral.tasks.identity.controllers;

import io.julienmetral.tasks.TestcontainersConfiguration;
import io.julienmetral.tasks.identity.services.IdenticonGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class IdenticonControllerTests {

    private static final String IDENTICON = "/api/v1/identicons/{id}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IdenticonGenerator generator;

    @Test
    void identiconIsServedAsSvgWithoutAToken() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(get(IDENTICON, id))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("image/svg+xml"))
                .andExpect(content().string(generator.svg(id)));
    }

    @Test
    void identiconIsCachedForAYearByAnyCache() throws Exception {
        mockMvc.perform(get(IDENTICON, UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=31536000")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("public")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("immutable")));
    }

    @Test
    void etagIsTheId() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(get(IDENTICON, id))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"" + id + "\""));
    }

    @Test
    void requestWithTheCurrentEtagIsAnsweredNotModified() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(get(IDENTICON, id).header(HttpHeaders.IF_NONE_MATCH, "\"" + id + "\""))
                .andExpect(status().isNotModified())
                .andExpect(content().string(""));
    }

    @Test
    void sameIdGivesTheSameImageOnEveryRequest() throws Exception {
        UUID id = UUID.randomUUID();

        String first = mockMvc.perform(get(IDENTICON, id)).andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(get(IDENTICON, id)).andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void anyWellFormedIdGetsAnImageWhetherAnAccountExistsOrNot() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get(IDENTICON, UUID.randomUUID()))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).startsWith("<svg ");
    }

    @Test
    void invalidIdGivesBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/identicons/not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyGetIsPublic() throws Exception {
        mockMvc.perform(post(IDENTICON, UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}

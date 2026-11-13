import { test } from "node:test";
import assert from "node:assert/strict";
import { trailerSource } from "../../src/main/resources/static/js/modules/movie-details.js";

test("supported YouTube URL formats resolve to the same restricted embed", () => {
  for (const url of ["https://youtube.com/watch?v=Abc123_-xyz&t=30", "https://youtu.be/Abc123_-xyz?si=foo", "https://www.youtube.com/embed/Abc123_-xyz", "https://m.youtube.com/shorts/Abc123_-xyz"]) {
    assert.equal(trailerSource(url).embed, "https://www.youtube-nocookie.com/embed/Abc123_-xyz");
    assert.equal(trailerSource(url).external, "https://www.youtube.com/watch?v=Abc123_-xyz");
  }
});
test("unsafe, malformed and missing trailers do not become links or frames", () => {
  for (const url of [null, "", "javascript:alert(1)", "data:text/html,test", "http://youtube.com/watch?v=Abc123_-xyz", "https://user:pass@example.com", "//youtube.com/watch?v=Abc123_-xyz", "invalid"]) {
    assert.equal(trailerSource(url), null);
  }
});
test("unrecognized providers and invalid video IDs only offer external HTTPS links", () => {
  for (const url of ["https://youtube.com.evil.test/watch?v=Abc123_-xyz", "https://www.youtube.com/watch?v=invalid", "https://vimeo.com/123456"]) {
    assert.equal(trailerSource(url).embed, null);
    assert.equal(trailerSource(url).external, url);
  }
});

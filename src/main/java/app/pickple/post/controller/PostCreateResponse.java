package app.pickple.post.controller;

import app.pickple.post.domain.Post;

public record PostCreateResponse(Long postId) {

    static PostCreateResponse from(Post post) {
        return new PostCreateResponse(post.id());
    }
}

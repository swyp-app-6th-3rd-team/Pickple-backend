package app.pickple.item.controller;

import app.pickple.auth.security.CurrentUser;
import app.pickple.common.ApiResponse;
import app.pickple.common.ResponseCode;
import app.pickple.item.domain.AttachType;
import app.pickple.item.domain.ItemContainer;
import app.pickple.item.service.ImageUploadService;
import app.pickple.item.service.ImageUploadService.UploadImage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class ImageUploadController {

    private final ImageUploadService imageUploadService;

    @Operation(summary = "이미지 업로드", description = "multipart images를 S3에 저장하고 부착에 쓸 itemContainerId를 반환합니다.")
    @PostMapping(path = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ImageUploadResponse> upload(
            @Parameter(hidden = true) @CurrentUser Long userId,
            @Parameter(description = "이미지 용도", required = true) @RequestParam AttachType attachType,
            @RequestPart("images") List<MultipartFile> images) {
        ItemContainer container = imageUploadService.upload(userId, attachType, toUploadImages(images));
        return ApiResponse.of(ResponseCode.CREATED, ImageUploadResponse.from(container));
    }

    private List<UploadImage> toUploadImages(List<MultipartFile> images) {
        List<UploadImage> requests = new ArrayList<>(images.size());
        for (MultipartFile image : images) {
            try {
                requests.add(new UploadImage(
                        image.getOriginalFilename(),
                        image.getContentType(),
                        image.getBytes()));
            } catch (IOException e) {
                throw new UncheckedIOException("업로드 요청의 이미지 파일을 읽지 못했습니다.", e);
            }
        }
        return requests;
    }
}

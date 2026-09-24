package com.mail.folder;

import com.mail.common.ApiResponse;
import com.mail.folder.domain.Folder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/folders")
@RequiredArgsConstructor
public class FolderController {

    private final FolderService folderService;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> tree() {
        return ApiResponse.ok(folderService.tree());
    }

    @PostMapping
    public ApiResponse<Folder> create(@RequestBody Folder folder) {
        return ApiResponse.ok(folderService.create(folder));
    }

    @PutMapping("/{id}")
    public ApiResponse<Folder> update(@PathVariable Long id, @RequestBody Folder folder) {
        return ApiResponse.ok(folderService.update(id, folder));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        folderService.delete(id);
        return ApiResponse.ok();
    }
}
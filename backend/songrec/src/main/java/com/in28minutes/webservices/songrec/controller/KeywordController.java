package com.in28minutes.webservices.songrec.controller;

import com.in28minutes.webservices.songrec.domain.keyword.Keyword;
import com.in28minutes.webservices.songrec.dto.request.KeywordCreateRequestDto;
import com.in28minutes.webservices.songrec.dto.response.keyword.KeywordResponseDto;
import com.in28minutes.webservices.songrec.service.KeywordService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Validated
public class KeywordController {
    private final KeywordService keywordService;

    @PostMapping("/keywords")
    public ResponseEntity<KeywordResponseDto> createKeyword(@Valid @RequestBody KeywordCreateRequestDto dto){
        KeywordResponseDto keyword =  KeywordResponseDto.from(keywordService.createKeyword(dto));
        return ResponseEntity.status(HttpStatus.CREATED).body(keyword);
    }

    @GetMapping("/keywords/{keywordId}")
    public KeywordResponseDto getKeyword(@PathVariable Long keywordId){
        return KeywordResponseDto.from(keywordService.getKeyword(keywordId));
    }

    @GetMapping("/keywords")
    public List<KeywordResponseDto> getAllKeywords(){
        List<Keyword> keywords = keywordService.getAllKeywords();
        return keywords.stream().map(KeywordResponseDto::from).toList();
    }


}

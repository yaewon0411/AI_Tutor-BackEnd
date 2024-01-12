package org.example.Assistant;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.example.Assistant.Enum.Personality;
import org.example.Assistant.Enum.SpeechLevel;
import org.example.Assistant.Enum.Voice;
import org.example.Assistant.dto.TutorInfoDto;
import org.example.Assistant.dto.TutorMessageDto;
import org.example.Assistant.dto.TutorModifyDto;
import org.example.Assistant.dto.TutoringPageDto;
import org.example.model.dto.*;
import org.example.model.dto.audio.AudioRequestDto;
import org.example.model.dto.openai.*;
import org.example.Assistant.dto.ModifyRequestDto;
import org.example.service.AssistantService;
import org.example.service.FileService;
import org.example.service.S3Service;
import org.json.JSONObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@RestController
@RequiredArgsConstructor
public class RealController {

    private final AssistantService assistantService;
    private final FileService fileService;
    private final RealService realService;
    private final S3Service s3Service;

    //홈 화면 - 어시스턴트 리스트
    @GetMapping("/home")
    public ResponseEntity<Object> home(){
        return ResponseEntity.ok(realService.findAll());
    }

    //어시스턴트 생성 : 등록한 파일이 있으면 먼저 서버에 저장 -> 파일 아이디 프론트에서 저장 -> (파일 있으면) 어시스턴트 code_interpreter로 생성하고 파일 넣기 -> 생성 완료
    @PostMapping("/create/assistant")
    public ResponseEntity<Object> createAssistant(
            @RequestParam("personality")String personality, @RequestParam("speechLevel")String speechLevel,
            @RequestParam("voice")String voice, @RequestParam("name")String name, @RequestParam("instruction")String instruction,
            @RequestParam("description")String description, @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable @RequestParam("file1") String file1, @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable @RequestParam("file2") String file2, @RequestParam("imgFile")MultipartFile file) throws IOException {

        //튜터 성향 뽑아서 instruction에 넣기
        String setInstruction = assistantService.setInstruction(instruction, personality, speechLevel);
        boolean hasFile = false;

        AssistantCreateRequestDto assistantCreateDto = new AssistantCreateRequestDto();
        assistantCreateDto.setName(name);
        assistantCreateDto.setInstruction(setInstruction);
        assistantCreateDto.setDescription(description);

        //등록된 파일 있으면 먼저 서버에 저장
        if(file1 != null){
            hasFile = true;
            ResponseEntity<String> response = fileService.uploadFile(file1);
            String fileId = fileService.getFileId(response);
            assistantCreateDto.setTools(Arrays.asList(
                    new Tool("code_interpreter"), new Tool("retrieval")));
            assistantCreateDto.getFileIds().add(fileId);
            if(file2!=null){
                ResponseEntity<String> response2 = fileService.uploadFile(file2);
                String fileId2 = fileService.getFileId(response2);
                assistantCreateDto.getFileIds().add(fileId2);
            }
        }
        //어시스턴트 생성
        ResponseEntity<Object> assistantObject = assistantService.createAssistant(assistantCreateDto);
        String assistantId = assistantService.getAssistantId(assistantObject);

        //이미지 버킷에 저장하고 저장된 경로 반환
        String imgUrl = s3Service.uploadImage(file);

        //db에 어시스턴트 insert
        Assistant assistant = new Assistant(assistantId, name, imgUrl, description, instruction, hasFile,
                Personality.valueOf(personality), SpeechLevel.valueOf(speechLevel), Voice.valueOf(voice));
        realService.save(assistant);

        return ResponseEntity.ok(assistantObject);
    }

    //튜터링 화면 - 상세 정보
    @GetMapping("/assistant/{assistantId}/info")
    public ResponseEntity<Object> tutoringInfo(@PathVariable("assistantId")String assistantId){
        TutorInfoDto res = realService.getTutorInfo(assistantId);
        return ResponseEntity.ok(res);
    }

    //튜터링 화면 - 채팅방 진입
    @GetMapping("/assistant/{assistantId}/chat")
    public ResponseEntity<Object> tutoringPage(@PathVariable("assistantId")String assistantId){
        TutoringPageDto res = realService.findByIdInTutoringPage(assistantId);
        return ResponseEntity.ok(res);
    }

    //스레드 생성하기
    @PostMapping("/assistant/create/thread")
    public ResponseEntity<Object> createThread(){
         return assistantService.createThreads();
    }

    //메시지 보내고 답변 받기 - 파일(사진 등)으로도 질문하는 경우로 수정
    @PostMapping("/assistant/{threadId}/get/message")
    public ResponseEntity<Object> getMessage(@PathVariable("threadId") String threadId, @RequestBody getMessageDto getMessageDto
    ) throws IOException {

        String fileId = "";
        //입력한 파일이 있으면
        if(getMessageDto.getFilePath() != null){
            ResponseEntity<String> response = fileService.uploadFile(getMessageDto.getFilePath());
            fileId = fileService.getFileId(response);
        }
        ArrayList<String> fileIds = new ArrayList<>();
        fileIds.add(fileId);

        //메시지 생성
        assistantService.createMessages(threadId, new MessagesRequestDto(getMessageDto.getContent(), fileIds));
        //런 생성
        ResponseEntity<Object> runs = assistantService.createRuns(threadId, new CreateRunsRequestDto(getMessageDto.getAssistantId()));
        //런 아이디 꺼내기
        String runId = assistantService.getRunId(runs);
        //런 실행 상태 추적
        ResponseEntity<Object> run = assistantService.getRun(threadId, runId);
        JSONObject object = new JSONObject(Objects.requireNonNull(run.getBody()));
        String status = object.get("status").toString();
        ChatDto chatDto = new ChatDto();
        int cnt = 0;

        while(status.equals("in_progress")) {
            System.out.println("cnt = " + cnt);
            ResponseEntity<Object> runForCheck = assistantService.getRun(threadId, runId);
            JSONObject objectForCheck = new JSONObject(Objects.requireNonNull(runForCheck.getBody()));

            if (objectForCheck.get("status").toString().equals("completed")) {
                //메시지 리스트 조회
                ResponseEntity<Object> messagesList = assistantService.getMessagesList(threadId);
                //답변 내보내기
                chatDto.setAnswer(assistantService.makeChat(messagesList).getAnswer());
                break;
            }
            try{
                Thread.sleep(1000);
            }catch(InterruptedException e){
                Thread.currentThread().interrupt();
                return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
            }
            cnt++;
        }
        //음성으로 질문한 거라면
        System.out.println("getMessageDto.getIsVoice() = " + getMessageDto.getIsVoice());
        if(getMessageDto.getIsVoice().equals("true")){
            //byte[] speech = assistantService.createSpeech2(new AudioRequestDto(chatDto.getAnswer()));
            System.out.println("음성 인터페이스 전환");
            String voice = realService.getAssistantVoice(getMessageDto.getAssistantId());
            ResponseEntity<Object> speech = assistantService.createSpeech(new AudioRequestDto(chatDto.getAnswer(), voice));
            TutorMessageDto res = new TutorMessageDto(chatDto, speech);
            return ResponseEntity.ok(res);
        }
        return ResponseEntity.ok(chatDto);
    }

    //채팅방 나갈 때 쓰레드 제거
    @PostMapping("/assistant/delete/thread/{threadId}")
    public ResponseEntity<Object> deleteThread(@PathVariable("threadId")String threadId){
        ResponseEntity<Object> res = assistantService.deleteThreads(threadId);
        return res;
    }

    //생성한 어시스턴트 수정 화면에 진입 - 저장된 정보 불러오기
    @GetMapping("/assistant/{assistantId}/modify")
    public ResponseEntity<Object> tutorModifyPage(@PathVariable("assistantId")String assistantId){

        List <String> fileNames = new ArrayList<>();
        Assistant findOne = realService.findById(assistantId);
        if(findOne.isHasFile()){
            ResponseEntity<Object> assistant = assistantService.searchAssistant(assistantId);
            List<String> fileIdList = assistantService.getFileIdList(assistant);
            for (String fileId : fileIdList) {
                String fileName = fileService.getFileName(fileId);
                fileNames.add(fileName);
            }
        }
        TutorModifyDto res = realService.getTutorInfoToModify(assistantId);
        res.setFileNames(fileNames);
        return ResponseEntity.ok(res);
    }


    //파일 없다가 추가하는 경우도 생각해야 함
    // 어시스턴트 수정하기 버튼 클릭 + 어시스턴트에 넣은 파일 삭제하기. 기존 정보에서 달라진 게 없으면 알아서 프론트에서 이거 못 날리게 막도록
    //어시스턴트 이미지도 수정헀을 때 반영되도록 해야 함
    @PostMapping("/modify/assistant/{assistantId}")
    public ResponseEntity<Object> modifyAssistant(@PathVariable("assistantId")String assistantId, @RequestBody ModifyRequestDto modifyRequestDto) throws IOException {

        //instruction, description, 기존에 있던 파일 삭제
        ResponseEntity<Object> res = assistantService.modifyAssistant(assistantId, modifyRequestDto);
        realService.modify(assistantId, modifyRequestDto.getPersonality(), modifyRequestDto.getSpeechLevel(),modifyRequestDto.getVoice());
        Assistant findOne = realService.findById(assistantId);

        //어시스턴트에 새로 파일이 추가되는 경우
        if((modifyRequestDto.getFilePath() != null) && !findOne.isHasFile()){
            for (String filePath : modifyRequestDto.getFilePath()) {
                fileService.uploadFile(filePath);
            }
            realService.setHasFileTrue(findOne);
        }
        return ResponseEntity.ok(res);
    }


    //사용자가 튜터 이미지를 변경했을 때만 작돟하도록 프론트에서 설정
    @PostMapping("/modify/assistant/{assistantId}/image")
    public ResponseEntity<Object> modifyAssistantImage(@PathVariable("assistantId")String assistantId,@RequestParam("imgFile")MultipartFile file ) throws MalformedURLException {

        Assistant findOne = realService.findById(assistantId);
        String imgPath = findOne.getImg();
        //버킷에서 이미지 삭제
        s3Service.deleteImage(imgPath);
        //버킷에 변경된 사진 업로드
        String img = s3Service.uploadImage(file);
        //튜터의 이미지 수정
        realService.modifyAssistantImg(assistantId, img);

        return ResponseEntity.ok("success");
    }


    //어시스턴트 삭제하기
    //수정 사항 : 나갈 때 전체 응답 정보가 나감 수정할 것
    //삭제하면서 버킷에 있는 이미지도 삭제하도록 추가
    @PostMapping("/delete/assistant/{assistantId}")
    public ResponseEntity<Object> deleteAssistant(@PathVariable("assistantId") String assistantId) throws MalformedURLException {
        //어시스턴트에 붙은 파일 있는 지 먼저 검사
        ResponseEntity<Object> assistant = assistantService.searchAssistant(assistantId);
        if(assistantService.hasFile(assistant)!=null){
             List<String> fileIdList = assistantService.getFileIdList(assistant);
             for (String fileId : fileIdList) {
                 //OpenAI 서버에 있는 파일 삭제
                 fileService.deleteFile(fileId);
             }
        }
        //버킷에서 이미지 삭제
        Assistant findOne = realService.findById(assistantId);
        String img = findOne.getImg();
        s3Service.deleteImage(img);

        //DB에서 어시스턴트 삭제
        realService.deleteAssistant(assistantId);

        //어시스턴트 최종 삭제
        ResponseEntity<Object> res = assistantService.deleteAssistant(assistantId);
        return ResponseEntity.ok("success-delete");
    }




}

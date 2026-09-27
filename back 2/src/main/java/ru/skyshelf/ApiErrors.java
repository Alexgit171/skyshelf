package ru.skyshelf;

import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.dao.DataIntegrityViolationException;

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> known(ResponseStatusException e) {return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",e.getReason()==null?"Ошибка запроса":e.getReason()));}
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException e) {return ResponseEntity.badRequest().body(Map.of("message","Проверьте поля: имя, корректный email и пароль от 10 символов."));}
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> tooLarge() {return ResponseEntity.status(413).body(Map.of("message","Максимальный размер файла — 20 МБ."));}
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> duplicate() {return ResponseEntity.status(409).body(Map.of("message","Такая запись уже существует."));}
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> malformed() {return ResponseEntity.badRequest().body(Map.of("message","Некорректный запрос."));}
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception e) {
        LoggerFactory.getLogger(getClass()).error("Request failed",e);
        return ResponseEntity.internalServerError().body(Map.of("message","Не удалось выполнить действие. Проверьте журнал сервера."));
    }
}

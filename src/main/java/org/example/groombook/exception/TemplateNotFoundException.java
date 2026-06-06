package org.example.groombook.exception;

/**
 * Шаблон расписания не найден по ID.
 */
public class TemplateNotFoundException extends GroomBookException {

    public TemplateNotFoundException(Long templateId) {
        super("Шаблон расписания #" + templateId + " не найден");
    }
}

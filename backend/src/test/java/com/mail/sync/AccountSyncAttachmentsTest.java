package com.mail.sync;

import com.mail.account.domain.MailAccount;
import com.mail.mail.domain.AttachmentMeta;
import com.mail.mail.domain.AttachmentMetaRepository;
import com.mail.mail.domain.Message;
import com.mail.mail.domain.MessageRepository;
import com.mail.mail.domain.PendingInlinePart;
import com.mail.storage.AttachmentStorage;
import com.mail.storage.StorageRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * AccountSync.persistAttachments 组合与加固测试：数量、disposition/contentId、
 * 超长附件名截断、hasAttach 置位、单附件异常隔离。用反射驱动私有方法，Mockito 打桩存储与仓储。
 */
class AccountSyncAttachmentsTest {

    private AttachmentMetaRepository metaRepo;
    private StorageRouter router;
    private AttachmentStorage storage;
    private MessageRepository messageRepo;
    private AccountSync sync;
    private Method persistAttachments;

    @BeforeEach
    void setUp() throws Exception {
        metaRepo = mock(AttachmentMetaRepository.class);
        when(metaRepo.save(any(AttachmentMeta.class))).thenAnswer(i -> i.getArgument(0));
        messageRepo = mock(MessageRepository.class);
        when(messageRepo.save(any(Message.class))).thenAnswer(i -> i.getArgument(0));

        storage = mock(AttachmentStorage.class);
        when(storage.type()).thenReturn("local");
        when(storage.save(anyString(), any(), anyLong(), any())).thenReturn("ref");
        router = mock(StorageRouter.class);
        when(router.defaultStorage()).thenReturn(storage);

        sync = new AccountSync(null, messageRepo, null, null, null, null, null, metaRepo, router, null);

        persistAttachments = AccountSync.class.getDeclaredMethod(
                "persistAttachments", MailAccount.class, Message.class);
        persistAttachments.setAccessible(true);
    }

    private MailAccount account() {
        MailAccount a = new MailAccount();
        a.setId(42L);
        a.setUserId(7L);
        a.setEmail("box@test.cn");
        return a;
    }

    private Message msgWith(PendingInlinePart... parts) {
        Message m = new Message();
        m.setId(100L);
        m.setFolderId(1L);
        for (PendingInlinePart p : parts) m.getPendingAttachments().add(p);
        return m;
    }

    private void invoke(Message m) throws Exception {
        persistAttachments.invoke(sync, account(), m);
    }

    @Test
    void persistsMultipleAttachmentsWithCorrectMetadata() throws Exception {
        Message m = msgWith(
                new PendingInlinePart(null, "报告.pdf", "application/pdf", new byte[]{1, 2, 3}),
                new PendingInlinePart(null, "转发邮件.eml", "message/rfc822", new byte[]{4, 5, 6, 7, 8}));

        invoke(m);

        ArgumentCaptor<AttachmentMeta> cap = ArgumentCaptor.forClass(AttachmentMeta.class);
        verify(metaRepo, times(2)).save(cap.capture());
        List<AttachmentMeta> saved = cap.getAllValues();

        for (AttachmentMeta meta : saved) {
            assertEquals("attachment", meta.getDisposition());
            assertNull(meta.getContentId(), "普通附件无 Content-ID");
            assertEquals(7L, meta.getUserId());
            assertEquals(42L, meta.getAccountId());
            assertEquals(100L, meta.getMessageId());
            assertEquals("local", meta.getStorageType());
        }
        assertEquals("报告.pdf", saved.get(0).getName());
        assertEquals(3L, saved.get(0).getSize());
        assertEquals("message/rfc822", saved.get(1).getMimeType());
        assertEquals(5L, saved.get(1).getSize());

        assertTrue(m.isHasAttach(), "采集成功后应置 hasAttach");
        assertTrue(m.getPendingAttachments().isEmpty(), "处理完应清空临时缓冲");
    }

    @Test
    void clampsOverlongAttachmentNameToColumnWidth() throws Exception {
        String longName = "档".repeat(600);
        Message m = msgWith(new PendingInlinePart(null, longName, "application/octet-stream", new byte[]{1}));

        invoke(m);

        ArgumentCaptor<AttachmentMeta> cap = ArgumentCaptor.forClass(AttachmentMeta.class);
        verify(metaRepo).save(cap.capture());
        assertTrue(cap.getValue().getName().length() <= 500,
                "超长附件名应截断到 500 以内，实际=" + cap.getValue().getName().length());
    }

    @Test
    void blankOrNullNameFallsBack() throws Exception {
        Message m = msgWith(
                new PendingInlinePart(null, "   ", "application/pdf", new byte[]{1}),
                new PendingInlinePart(null, null, "image/png", new byte[]{2}));
        invoke(m);

        ArgumentCaptor<AttachmentMeta> cap = ArgumentCaptor.forClass(AttachmentMeta.class);
        verify(metaRepo, times(2)).save(cap.capture());
        for (AttachmentMeta meta : cap.getAllValues()) {
            assertNotNull(meta.getName());
            assertFalse(meta.getName().isBlank(), "空/空白名应回退为 attachment");
        }
    }

    @Test
    void oneStorageFailureDoesNotBlockOthers() throws Exception {
        // 第一次保存抛异常（模拟磁盘故障），第二次成功：单附件失败不应阻断其余落库
        AttachmentStorage flaky = mock(AttachmentStorage.class);
        when(flaky.type()).thenReturn("local");
        when(flaky.save(anyString(), any(), anyLong(), any()))
                .thenThrow(new RuntimeException("boom"))   // 第一封
                .thenReturn("ref-ok");                      // 第二封
        when(router.defaultStorage()).thenReturn(flaky);

        Message m = msgWith(
                new PendingInlinePart(null, "a.pdf", "application/pdf", new byte[]{1}),
                new PendingInlinePart(null, "b.pdf", "application/pdf", new byte[]{2}));
        invoke(m);

        verify(metaRepo, times(1)).save(any(AttachmentMeta.class)); // 只有第二封成功入库
        assertTrue(m.isHasAttach(), "只要有一个成功就应置 hasAttach");
        assertTrue(m.getPendingAttachments().isEmpty());
    }

    @Test
    void emptyPendingIsNoOp() throws Exception {
        Message m = msgWith();
        invoke(m);
        verifyNoInteractions(metaRepo);
        assertFalse(m.isHasAttach());
    }
}

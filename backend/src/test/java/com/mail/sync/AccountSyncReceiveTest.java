package com.mail.sync;

import com.mail.account.domain.MailAccount;
import com.mail.account.domain.MailAccountRepository;
import com.mail.folder.FolderService;
import com.mail.mail.domain.AttachmentMeta;
import com.mail.mail.domain.AttachmentMetaRepository;
import com.mail.mail.domain.Message;
import com.mail.mail.domain.MessageRepository;
import com.mail.mail.domain.PendingInlinePart;
import com.mail.notification.NotificationService;
import com.mail.protocol.MailClient;
import com.mail.protocol.ProtocolFactory;
import com.mail.push.PushService;
import com.mail.rule.RuleEngine;
import com.mail.rule.domain.RuleRepository;
import com.mail.storage.AttachmentStorage;
import com.mail.storage.StorageRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * doSync 收件编排的组合验证：新邮件落库采集附件；已入库旧邮件在重新解析到附件时回填，
 * 修复“外部来信带附件但页面不显示”的历史缺陷（去重分支此前从不补附件元数据）。
 */
class AccountSyncReceiveTest {

    private MailAccountRepository accountRepo;
    private MessageRepository messageRepo;
    private ProtocolFactory protocolFactory;
    private FolderService folderService;
    private RuleEngine ruleEngine;
    private RuleRepository ruleRepo;
    private PushService pushService;
    private AttachmentMetaRepository metaRepo;
    private StorageRouter router;
    private AttachmentStorage storage;
    private NotificationService notification;
    private AccountSync sync;

    @BeforeEach
    void setUp() {
        accountRepo = mock(MailAccountRepository.class);
        messageRepo = mock(MessageRepository.class);
        protocolFactory = mock(ProtocolFactory.class);
        folderService = mock(FolderService.class);
        ruleEngine = mock(RuleEngine.class);
        ruleRepo = mock(RuleRepository.class);
        pushService = mock(PushService.class);
        metaRepo = mock(AttachmentMetaRepository.class);
        storage = mock(AttachmentStorage.class);
        router = mock(StorageRouter.class);
        notification = mock(NotificationService.class);

        lenient().when(accountRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(messageRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(metaRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(storage.type()).thenReturn("local");
        lenient().when(storage.save(anyString(), any(), anyLong(), any())).thenReturn("ref");
        lenient().when(router.defaultStorage()).thenReturn(storage);
        lenient().when(folderService.resolveAccountFolderId(anyLong(), anyLong(), anyString())).thenReturn(1L);
        lenient().when(ruleRepo.findByUserIdAndAccountIdAndEnabledTrueOrderByPriorityAsc(anyLong(), anyLong()))
                .thenReturn(List.of());
        lenient().when(ruleEngine.apply(anyLong(), any(), any())).thenReturn(false);

        sync = new AccountSync(accountRepo, messageRepo, protocolFactory, folderService,
                ruleEngine, ruleRepo, pushService, metaRepo, router, notification);
    }

    private MailAccount account() {
        MailAccount a = new MailAccount();
        a.setId(42L);
        a.setUserId(7L);
        a.setEmail("box@ext.test");
        a.setHost("imap.ext.test");
        a.setProtocol("pop3"); // 走全量分支，避免 maxRemoteSeq 依赖
        return a;
    }

    private Message fetched(String uid, PendingInlinePart... atts) {
        Message m = new Message();
        m.setUserId(7L);
        m.setAccountId(42L);
        m.setFolderId(1L);
        m.setRemoteUid(uid);
        m.setSubject("带附件来信");
        m.setBodyHtml("<p>hi</p>");
        m.setBodyText("hi");
        for (PendingInlinePart p : atts) m.getPendingAttachments().add(p);
        return m;
    }

    private void stubClient(List<Message> msgs) {
        MailClient client = mock(MailClient.class);
        when(client.fetchInbox(any(), anyInt())).thenReturn(msgs);
        when(protocolFactory.clientFor("pop3")).thenReturn(client);
    }

    @Test
    void newMessagePersistsAttachments() {
        Message m = fetched("9", new PendingInlinePart(null, "invoice.pdf", "application/pdf", new byte[]{1, 2}));
        stubClient(List.of(m));
        when(messageRepo.findByAccountIdAndFolderIdAndRemoteUid(eq(42L), eq(1L), eq("9"))).thenReturn(Optional.empty());

        sync.sync(account());

        ArgumentCaptor<AttachmentMeta> cap = ArgumentCaptor.forClass(AttachmentMeta.class);
        verify(metaRepo).save(cap.capture());
        assertEquals("attachment", cap.getValue().getDisposition());
        assertEquals("invoice.pdf", cap.getValue().getName());
        assertNull(cap.getValue().getContentId());
    }

    @Test
    void dedupExistingBackfillsMissingAttachments() {
        Message existingStored = new Message();
        existingStored.setId(100L);
        existingStored.setBodyHtml("<p>hi</p>"); // 正文已在，走回填而非补正文
        existingStored.setHasAttach(false);

        Message m = fetched("9", new PendingInlinePart(null, "邮件副本.eml", "message/rfc822", new byte[]{5, 6, 7}));
        stubClient(List.of(m));
        when(messageRepo.findByAccountIdAndFolderIdAndRemoteUid(eq(42L), eq(1L), eq("9")))
                .thenReturn(Optional.of(existingStored));
        when(metaRepo.existsByMessageIdAndDisposition(eq(100L), eq("attachment"))).thenReturn(false);

        sync.sync(account());

        ArgumentCaptor<AttachmentMeta> cap = ArgumentCaptor.forClass(AttachmentMeta.class);
        verify(metaRepo).save(cap.capture());
        assertEquals(100L, cap.getValue().getMessageId(), "回填应挂到已存在邮件");
        assertEquals("attachment", cap.getValue().getDisposition());
        assertTrue(existingStored.isHasAttach(), "回填后应置 hasAttach，列表才显示回形标");
    }

    @Test
    void dedupSkipsWhenAttachmentsAlreadyPersisted() {
        Message existingStored = new Message();
        existingStored.setId(100L);
        existingStored.setBodyHtml("<p>hi</p>");
        existingStored.setHasAttach(true);

        Message m = fetched("9", new PendingInlinePart(null, "x.pdf", "application/pdf", new byte[]{1}));
        stubClient(List.of(m));
        when(messageRepo.findByAccountIdAndFolderIdAndRemoteUid(eq(42L), eq(1L), eq("9")))
                .thenReturn(Optional.of(existingStored));
        when(metaRepo.existsByMessageIdAndDisposition(eq(100L), eq("attachment"))).thenReturn(true);

        sync.sync(account());

        verify(metaRepo, never()).save(any(AttachmentMeta.class)); // 已有附件元数据，不重复采集
    }
}

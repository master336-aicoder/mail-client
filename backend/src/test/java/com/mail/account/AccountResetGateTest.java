package com.mail.account;

import com.mail.account.domain.MailAccount;
import com.mail.account.domain.MailAccountRepository;
import com.mail.common.BizException;
import com.mail.auth.CurrentUser;
import com.mail.folder.FolderService;
import com.mail.mail.SmtpTransport;
import com.mail.mail.domain.AttachmentMetaRepository;
import com.mail.mail.domain.MessageRepository;
import com.mail.notification.NotificationService;
import com.mail.protocol.ProtocolFactory;
import com.mail.storage.StorageRouter;
import com.mail.task.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 重置账户的安全门禁：只有账户处于停用状态且本轮同步已结束才允许清空。
 * 停用会自动使该账户的周期同步任务失效（TaskService.ensureAccountSyncTask）。
 */
class AccountResetGateTest {

    private MailAccountRepository accountRepo;
    private TaskService taskService;
    private MessageRepository messageRepo;
    private AttachmentMetaRepository metaRepo;
    private StorageRouter storageRouter;
    private NotificationService notification;
    private AccountService svc;

    @BeforeEach
    void setUp() {
        accountRepo = mock(MailAccountRepository.class);
        taskService = mock(TaskService.class);
        messageRepo = mock(MessageRepository.class);
        metaRepo = mock(AttachmentMetaRepository.class);
        storageRouter = mock(StorageRouter.class);
        notification = mock(NotificationService.class);
        when(metaRepo.findByAccountId(anyLong())).thenReturn(List.of());
        when(metaRepo.deleteAllByAccountId(anyLong())).thenReturn(0);
        when(messageRepo.deleteAllByAccountId(anyLong())).thenReturn(0);

        svc = new AccountService(
                accountRepo,
                mock(com.mail.common.CryptoService.class),
                mock(FolderService.class),
                mock(com.mail.common.HostGuard.class),
                mock(ProtocolFactory.class),
                mock(SmtpTransport.class),
                taskService,
                notification,
                messageRepo,
                metaRepo,
                storageRouter);

        CurrentUser.set(7L);
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    private MailAccount stub(long id, boolean active) {
        MailAccount a = new MailAccount();
        a.setId(id);
        a.setUserId(7L);
        a.setEmail("box@test.local");
        a.setActive(active);
        when(accountRepo.findById(id)).thenReturn(Optional.of(a));
        return a;
    }

    @Test
    void refusesActiveAccount() {
        stub(1L, true);
        BizException ex = assertThrows(BizException.class, () -> svc.reset(1L));
        assertEquals(40010, ex.getCode());
        verify(messageRepo, never()).deleteAllByAccountId(anyLong());
        verify(metaRepo, never()).deleteAllByAccountId(anyLong());
    }

    @Test
    void refusesWhenSyncRunStillActive() {
        MailAccount a = stub(2L, false);
        when(taskService.syncRunActive(a)).thenReturn(true);
        BizException ex = assertThrows(BizException.class, () -> svc.reset(2L));
        assertEquals(40011, ex.getCode());
        verify(messageRepo, never()).deleteAllByAccountId(anyLong());
    }

    @Test
    void proceedsWhenDeactivatedAndIdle() {
        MailAccount a = stub(3L, false);
        when(taskService.syncRunActive(a)).thenReturn(false);
        var result = svc.reset(3L);
        assertEquals(3L, result.get("accountId"));
        verify(messageRepo).deleteAllByAccountId(3L);
        verify(metaRepo).deleteAllByAccountId(3L);
        verify(notification).notify(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsForeignAccount() {
        MailAccount a = new MailAccount();
        a.setId(4L);
        a.setUserId(99L);
        when(accountRepo.findById(4L)).thenReturn(Optional.of(a));
        BizException ex = assertThrows(BizException.class, () -> svc.reset(4L));
        assertEquals(40300, ex.getCode());
        verify(messageRepo, never()).deleteAllByAccountId(anyLong());
    }

    @Test
    void unknownAccountFails() {
        when(accountRepo.findById(50L)).thenReturn(Optional.empty());
        BizException ex = assertThrows(BizException.class, () -> svc.reset(50L));
        assertNotNull(ex.getMessage());
    }

    @Test
    void ignoresMissingArgumentOnDeleteCalls() {
        // 保证 reset 不会去触碰别的账户的数据
        stub(6L, false);
        svc.reset(6L);
        verify(messageRepo).deleteAllByAccountId(6L);
        verifyNoMoreInteractions(messageRepo);
    }
}

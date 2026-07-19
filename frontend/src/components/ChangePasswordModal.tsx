import { useState } from 'react';
import { Alert, Button, Form, Input, Modal, message } from 'antd';
import { LockOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { changePassword } from '../api/auth';
import { useAuthStore } from '../store/authStore';

type PasswordFormValue = {
  currentPassword: string;
  newPassword: string;
  confirmPassword: string;
};

type ChangePasswordModalProps = {
  compact?: boolean;
};

/** 当前用户修改密码入口；成功后强制重新登录，确保旧令牌立即退出。 */
const ChangePasswordModal = ({ compact = false }: ChangePasswordModalProps) => {
  const [form] = Form.useForm<PasswordFormValue>();
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const navigate = useNavigate();
  const clearAuth = useAuthStore((state) => state.clearAuth);

  const close = () => {
    setOpen(false);
    form.resetFields();
  };

  const submit = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      const result = await changePassword({
        currentPassword: values.currentPassword,
        newPassword: values.newPassword,
      });
      if (result.code !== 200) {
        message.error(result.message || '密码修改失败');
        return;
      }
      message.success('密码已修改，请使用新密码重新登录');
      close();
      clearAuth();
      navigate('/login', { replace: true });
    } catch (error) {
      message.error(error instanceof Error ? error.message : '密码修改失败');
    } finally {
      setSaving(false);
    }
  };

  return (
    <>
      <Button
        type={compact ? 'text' : 'default'}
        size={compact ? 'small' : 'middle'}
        icon={<LockOutlined />}
        title="修改密码"
        onClick={() => setOpen(true)}
      >
        {compact ? null : '修改密码'}
      </Button>
      <Modal
        title="修改登录密码"
        open={open}
        okText="确认修改"
        cancelText="取消"
        confirmLoading={saving}
        onOk={submit}
        onCancel={close}
        destroyOnHidden
      >
        <Alert
          type="info"
          showIcon
          message="修改成功后，当前设备和其他设备上的旧登录令牌都会失效。"
          style={{ marginBottom: 18 }}
        />
        <Form form={form} layout="vertical" requiredMark={false}>
          <Form.Item
            name="currentPassword"
            label="当前密码"
            rules={[{ required: true, message: '请输入当前密码' }]}
          >
            <Input.Password autoComplete="current-password" placeholder="请输入当前登录密码" />
          </Form.Item>
          <Form.Item
            name="newPassword"
            label="新密码"
            extra="8～128 个字符，至少包含字母、数字、符号或空格中的两类。"
            rules={[
              { required: true, message: '请输入新密码' },
              { min: 8, max: 128, message: '新密码长度必须为 8～128 个字符' },
            ]}
          >
            <Input.Password autoComplete="new-password" placeholder="设置一个容易记住但不易猜到的密码" />
          </Form.Item>
          <Form.Item
            name="confirmPassword"
            label="确认新密码"
            dependencies={['newPassword']}
            rules={[
              { required: true, message: '请再次输入新密码' },
              ({ getFieldValue }) => ({
                validator(_, value) {
                  return !value || value === getFieldValue('newPassword')
                    ? Promise.resolve()
                    : Promise.reject(new Error('两次输入的新密码不一致'));
                },
              }),
            ]}
          >
            <Input.Password autoComplete="new-password" placeholder="再次输入新密码" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
};

export default ChangePasswordModal;

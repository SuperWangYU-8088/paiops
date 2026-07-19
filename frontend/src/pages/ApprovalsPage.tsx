import { useCallback, useEffect, useState } from 'react';
import { Button, Empty, Input, message, Modal, Select, Spin } from 'antd';
import { CheckOutlined, CloseOutlined, ReloadOutlined } from '@ant-design/icons';
import { ApprovalRequest, getApprovals, reviewApproval } from '../api/ops';
import OpsLayout from '../components/OpsLayout';
import { OpsStatus } from '../components/OpsStatus';
import { formatDateTime } from '../utils/format';

const ApprovalsPage = () => {
  const [approvals, setApprovals] = useState<ApprovalRequest[]>([]);
  const [status, setStatus] = useState<string>();
  const [loading, setLoading] = useState(true);
  const [reviewing, setReviewing] = useState<{ item: ApprovalRequest; approved: boolean }>();
  const [comment, setComment] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await getApprovals(status);
      setApprovals(result.code === 200 ? result.data : []);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '审批列表加载失败');
    } finally {
      setLoading(false);
    }
  }, [status]);

  useEffect(() => {
    load();
  }, [load]);

  const submitReview = async () => {
    if (!reviewing) return;
    setSubmitting(true);
    try {
      await reviewApproval(reviewing.item.id, reviewing.approved, comment);
      message.success(reviewing.approved ? '审批已通过，任务将从断点继续' : '审批已拒绝');
      setReviewing(undefined);
      setComment('');
      await load();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '审批处理失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <OpsLayout
      eyebrow="Governance"
      title="审批中心"
      description="审核高风险变更并保留完整审批链"
      actions={<Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>}
    >
      <div className="ops-filterbar">
        <Select
          allowClear
          value={status}
          placeholder="全部状态"
          options={[
            { value: 'PENDING', label: '待处理' },
            { value: 'APPROVED', label: '已批准' },
            { value: 'REJECTED', label: '已拒绝' },
            { value: 'EXPIRED', label: '已过期' },
          ]}
          onChange={setStatus}
        />
        <span>{approvals.length} 条审批记录</span>
      </div>

      <section className="ops-data-panel">
        {loading ? (
          <div className="ops-page-loading"><Spin /></div>
        ) : approvals.length === 0 ? (
          <Empty description="暂无审批记录" />
        ) : (
          <div className="ops-data-table">
            <div className="ops-table-row ops-table-head ops-approval-columns">
              <span>审批项</span><span>风险</span><span>状态</span><span>申请人</span><span>申请时间</span><span>操作</span>
            </div>
            {approvals.map((approval) => (
              <div className="ops-table-row ops-approval-columns" key={approval.id}>
                <span className="ops-table-primary">
                  <strong>{approval.nodeName || approval.nodeId}</strong>
                  <small>执行 #{approval.executionId} · {approval.requestReason || 'Runbook 变更审批'}</small>
                </span>
                <span className="ops-risk-label is-high">{approval.riskLevel}</span>
                <OpsStatus status={approval.status} />
                <span>{approval.requestedBy || 'system'}</span>
                <span>{formatDateTime(approval.requestedAt)}</span>
                <span className="ops-row-actions">
                  {approval.status === 'PENDING' && (
                    <>
                      <Button
                        size="small"
                        type="primary"
                        icon={<CheckOutlined />}
                        onClick={() => setReviewing({ item: approval, approved: true })}
                      >
                        批准
                      </Button>
                      <Button
                        size="small"
                        danger
                        icon={<CloseOutlined />}
                        onClick={() => setReviewing({ item: approval, approved: false })}
                      >
                        拒绝
                      </Button>
                    </>
                  )}
                </span>
              </div>
            ))}
          </div>
        )}
      </section>

      <Modal
        title={reviewing?.approved ? '批准变更' : '拒绝变更'}
        open={Boolean(reviewing)}
        okText={reviewing?.approved ? '批准并继续' : '确认拒绝'}
        okButtonProps={{ danger: reviewing?.approved === false, loading: submitting }}
        onOk={submitReview}
        onCancel={() => {
          setReviewing(undefined);
          setComment('');
        }}
      >
        <Input.TextArea
          rows={4}
          value={comment}
          placeholder="审批意见（可选）"
          onChange={(event) => setComment(event.target.value)}
        />
      </Modal>
    </OpsLayout>
  );
};

export default ApprovalsPage;

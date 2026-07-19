import { ArrowLeftOutlined, HomeOutlined } from '@ant-design/icons';
import { Button, Space, Tooltip } from 'antd';
import { useLocation, useNavigate } from 'react-router-dom';

const AppNavigation = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const isHome = location.pathname === '/';

  const handleBack = () => {
    if (location.key === 'default') {
      navigate('/');
      return;
    }
    navigate(-1);
  };

  return (
    <Space size={8}>
      <Tooltip title="返回上一页">
        <Button
          icon={<ArrowLeftOutlined />}
          aria-label="返回上一页"
          disabled={isHome}
          onClick={handleBack}
        />
      </Tooltip>
      <Tooltip title="回到主页">
        <Button
          icon={<HomeOutlined />}
          aria-label="回到主页"
          disabled={isHome}
          onClick={() => navigate('/')}
        />
      </Tooltip>
    </Space>
  );
};

export default AppNavigation;

import { createBrowserRouter } from 'react-router-dom';
import { lazy, Suspense } from 'react';
import type { ReactNode } from 'react';
import { Spin } from 'antd';
import { ROUTES } from '@/constants/routes';
import App from './App';

const TenantList        = lazy(() => import('@/pages/tenant-list'));
const SceneList          = lazy(() => import('@/pages/scene-list'));
const SceneDetail        = lazy(() => import('@/pages/scene-detail'));
const SceneEdit          = lazy(() => import('@/pages/scene-edit'));
const RuleList           = lazy(() => import('@/pages/rule-list'));
const RulesAll           = lazy(() => import('@/pages/rules-all'));
const RuleEditor         = lazy(() => import('@/pages/rule-editor'));
const MetricList         = lazy(() => import('@/pages/metric-list'));
const MetricDetail       = lazy(() => import('@/pages/metric-detail'));
const ConnectorList      = lazy(() => import('@/pages/connector-list'));
const ConnectorDetail    = lazy(() => import('@/pages/connector-detail'));
const DecisionList       = lazy(() => import('@/pages/decision-list'));
const DecisionDetail     = lazy(() => import('@/pages/decision-detail'));
const EvalSession        = lazy(() => import('@/pages/eval-session'));
const EvalSessionDetail  = lazy(() => import('@/pages/eval-session-detail'));
const AuditLog           = lazy(() => import('@/pages/audit-log'));
const Effectiveness      = lazy(() => import('@/pages/effectiveness'));
const ScheduledTaskList   = lazy(() => import('@/pages/scheduled-task-list'));
const ScheduledTaskDetail = lazy(() => import('@/pages/scheduled-task-detail'));
const ImportExport       = lazy(() => import('@/pages/import-export'));

const LazyPage = ({ children }: { children: ReactNode }) => (
  <Suspense fallback={<Spin size="large" style={{ display: 'block', margin: '100px auto' }} />}>
    {children}
  </Suspense>
);

export const router = createBrowserRouter([
  {
    path: '/',
    element: <App />,
    children: [
      { index: true, element: <LazyPage><SceneList /></LazyPage> },
      { path: ROUTES.TENANTS,        element: <LazyPage><TenantList /></LazyPage> },
      { path: ROUTES.SCENES,         element: <LazyPage><SceneList /></LazyPage> },
      { path: ROUTES.SCENE_DETAIL,   element: <LazyPage><SceneDetail /></LazyPage> },
      { path: ROUTES.SCENE_EDIT,     element: <LazyPage><SceneEdit /></LazyPage> },
      { path: ROUTES.SCENE_RULES,    element: <LazyPage><RuleList /></LazyPage> },
      { path: ROUTES.RULES,          element: <LazyPage><RulesAll /></LazyPage> },
      { path: ROUTES.RULE_EDITOR,    element: <LazyPage><RuleEditor /></LazyPage> },
      { path: ROUTES.METRICS,        element: <LazyPage><MetricList /></LazyPage> },
      { path: ROUTES.METRIC_DETAIL,  element: <LazyPage><MetricDetail /></LazyPage> },
      { path: ROUTES.CONNECTORS,        element: <LazyPage><ConnectorList /></LazyPage> },
      { path: ROUTES.CONNECTOR_NEW,     element: <LazyPage><ConnectorDetail /></LazyPage> },
      { path: ROUTES.CONNECTOR_DETAIL,  element: <LazyPage><ConnectorDetail /></LazyPage> },
      { path: ROUTES.DECISIONS,      element: <LazyPage><DecisionList /></LazyPage> },
      { path: ROUTES.DECISION_DETAIL, element: <LazyPage><DecisionDetail /></LazyPage> },
      { path: ROUTES.SESSIONS,       element: <LazyPage><EvalSession /></LazyPage> },
      { path: ROUTES.SESSION_DETAIL, element: <LazyPage><EvalSessionDetail /></LazyPage> },
      { path: ROUTES.EFFECTIVENESS,  element: <LazyPage><Effectiveness /></LazyPage> },
      { path: ROUTES.AUDIT_LOGS,     element: <LazyPage><AuditLog /></LazyPage> },
      { path: ROUTES.SCHEDULED_TASKS,       element: <LazyPage><ScheduledTaskList /></LazyPage> },
      { path: ROUTES.SCHEDULED_TASK_DETAIL, element: <LazyPage><ScheduledTaskDetail /></LazyPage> },
      { path: ROUTES.IMPORT_EXPORT,  element: <LazyPage><ImportExport /></LazyPage> },
    ],
  },
]);

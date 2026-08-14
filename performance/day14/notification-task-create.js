import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';

const tenantId = __ENV.TENANT_ID;
const applicationId = Number(__ENV.APPLICATION_ID);
const templateId = Number(__ENV.TEMPLATE_ID);

if (!tenantId
    || !applicationId
    || !templateId) {
    throw new Error(
        '必须传入TENANT_ID、APPLICATION_ID和TEMPLATE_ID'
    );
}

const receiver = __ENV.RECEIVER || '13800138000';

const templateParams = JSON.parse(
    __ENV.TEMPLATE_PARAMS_JSON
    || '{"name":"Day14"}'
);

const businessErrors =
    new Rate('task_create_business_errors');

export const options = {
    scenarios: {
        createNotificationTask: {
            executor: 'constant-arrival-rate',
            rate: Number(__ENV.RATE || 5),
            timeUnit: '1s',
            duration: __ENV.DURATION || '2m',
            preAllocatedVUs: Number(
                __ENV.PRE_ALLOCATED_VUS || 20
            ),
            maxVUs: Number(__ENV.MAX_VUS || 200),
        },
    },

    thresholds: {
        task_create_business_errors: [
            'rate<0.01',
        ],
        http_req_failed: [
            'rate<0.01',
        ],
        'http_req_duration{endpoint:notification_task_create}': [
            'p(95)<500',
            'p(99)<1000',
        ],
    },
};

export default function () {
    /*
     * notify_task上存在：
     * unique(tenant_id, application_id, request_id)
     *
     * 因此压测请求必须使用不同requestId。
     */
    const requestId =
        `day14-${Date.now()}-${__VU}-${__ITER}`;

    const payload = JSON.stringify({
        requestId,
        applicationId,
        templateId,
        recipients: [
            {
                receiver,
                params: templateParams,
            },
        ],
    });

    const response = http.post(
        `${baseUrl}/api/v1/notification-tasks`,
        payload,
        {
            headers: {
                'Content-Type': 'application/json',
                'X-Tenant-Id': tenantId,
            },
            tags: {
                endpoint: 'notification_task_create',
            },
        }
    );

    const passed = check(response, {
        'status is 200': (result) =>
            result.status === 200,

        'business code is success': (result) => {
            try {
                return result.json('code') === '000000';
            } catch (error) {
                return false;
            }
        },
    });

    businessErrors.add(!passed);
}
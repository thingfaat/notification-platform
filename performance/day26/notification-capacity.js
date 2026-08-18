import http from 'k6/http';
import {check} from 'k6';
import {Rate} from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const tenantId = __ENV.TENANT_ID;

// 19 位 Java Long 不能转 JavaScript Number，始终按字符串发送。
const applicationId = __ENV.APPLICATION_ID;
const templateId = __ENV.TEMPLATE_ID;

if (!tenantId || !applicationId || !templateId) {
    throw new Error('必须传 TENANT_ID、APPLICATION_ID、TEMPLATE_ID');
}

const receiver = __ENV.RECEIVER || '13800138000';
const businessErrors = new Rate('task_create_business_errors');

export const options = {
    scenarios: {
        createNotificationTask: {
            executor: 'constant-arrival-rate',
            rate: Number(__ENV.RATE || 10),
            timeUnit: '1s',
            duration: __ENV.DURATION || '2m',
            preAllocatedVUs: Number(__ENV.PRE_ALLOCATED_VUS || 20),
            maxVUs: Number(__ENV.MAX_VUS || 200),
        },
    },
    thresholds: {
        task_create_business_errors: ['rate<0.01'],
        http_req_failed: ['rate<0.01'],
        'http_req_duration{endpoint:notification_task_create}': [
            'p(95)<500',
            'p(99)<1000',
        ],
    },
};

export default function () {
    const requestId = `day26-${Date.now()}-${__VU}-${__ITER}`;
    const payload = JSON.stringify({
        requestId,
        applicationId,
        templateId,
        recipients: [
            {
                receiver,
                params: {name: 'Day26'},
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
            tags: {endpoint: 'notification_task_create'},
        }
    );

    const passed = check(response, {
        'status is 200': (result) => result.status === 200,
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
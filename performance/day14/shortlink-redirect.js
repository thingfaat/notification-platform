import http from 'k6/http';
import {check} from 'k6';
import {Rate} from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';

const shortCode = __ENV.SHORT_CODE;

if (!shortCode) {
    throw new Error('必须通过SHORT_CODE传入一个有效的8位短码');
}

const businessErrors = new Rate("shortlink_business_errors");

export const options = {
    discardResponseBodies: true,
    scenarios: {
        shortLinkRedirect: {
            executor: 'constant-arrival-rate',
            rate: Number(__ENV.RATE || 50),
            timeUnit: '1s',
            duration: __ENV.DURATION || '2m',
            preAllocatedVUs: Number(
                __ENV.PRE_ALLOCATED_VUS || 50
            ),
            maxVUs: Number(__ENV.MAX_VUS || 500),
        },
    },

    thresholds: {
        shortlink_business_errors: [
            'rate<0.01',
        ],
        http_req_failed: [
            'rate<0.01',
        ],
        'http_req_duration{endpoint:shortlink_redirect}': [
            'p(95)<200',
            'p(99)<500',
        ],
    },
};

export default function () {
    const response = http.get(
        `${baseUrl}/s/${shortCode}`,
        {
            redirects: 0,
            tags: {
                endpoint: 'shortlink_redirect',
            },
        }
    );

    const passed = check(response, {
        'status is 302': (result) =>
            result.status === 302,

        'Location header exists': (result) =>
            Boolean(result.headers.Location),
    });

    businessErrors.add(!passed);
}
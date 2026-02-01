import axios from 'axios';
import { testConfig } from './utils/test-config';

async function getProjectDetails(projectKey: string) {
    const auth = Buffer.from(`${testConfig.jira.email}:${testConfig.jira.apiToken}`).toString('base64');
    try {
        const response = await axios.get(`${testConfig.jira.baseUrl}/rest/api/3/project/${projectKey}`, {
            headers: {
                'Authorization': `Basic ${auth}`,
                'Accept': 'application/json'
            }
        });
        console.log(`Issue Types for ${projectKey}:`);
        response.data.issueTypes.forEach((t: any) => {
            console.log(`- ${t.name} (id: ${t.id})`);
        });
    } catch (error: any) {
        console.error('Error fetching project details:', error.message);
    }
}

getProjectDetails('CRM');

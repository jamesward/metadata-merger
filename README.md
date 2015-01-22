Salesforce Metadata Merger
--------------------------

A tool to diff and merge Salesforce metadata across orgs.

For your health and safety you should deploy this on your own Heroku environment:
[![Deploy on Heroku](https://www.herokucdn.com/deploy/button.png)](https://heroku.com/deploy?template=https://github.com/jamesward/metadata-merger)

Fill in the required fields.  For the `FORCE_CONSUMER_KEY` and `FORCE_CONSUMER_SECRET` you will need to [create a connected app](https://www.salesforce.com/us/developer/docs/api_rest/Content/intro_defining_remote_access_applications.htm) in any organization on Salesforce.  The `Callback URL` will be `https://YOUR_HEROKU_APP.herokuapp.com/_oauth_callback` (replace `YOUR_HEROKU_APP` with the name you select or are assigned).

For demo purposes try out the [Salesforce Metadata Merger Demo](https://metadata-merger.herokuapp.com).

Right now the Salesforce Metadata Merger only supports Apex classes.  If you need additional metadata types, [issues/new](file an issue).